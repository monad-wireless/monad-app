package sk.martinvanco.monad.lab.data

import io.github.aakira.napier.Napier
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.core.util.Platform
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.TelemetrySink
import sk.martinvanco.monad.lab.domain.monotonicNanos
import sk.martinvanco.monad.lab.domain.health.InstrumentHealth
import sk.martinvanco.monad.lab.domain.TelemetryPosture
import sk.martinvanco.monad.lab.domain.health.StreamHealth

/**
 * Ships the instrument's own health to the lab's observability stack while a session is running.
 *
 * WHY THIS EXISTS
 * ---------------
 * The instrument already computes, once a second, whether every stream is producing at the pace it
 * was commanded ([LabInstrument.health]). Until now that answer went to exactly two places, and
 * neither could be reached during a measurement: the console on the operator's own screen, and
 * `log.tsv` inside the session, which reaches object storage only after the walk ends.
 *
 * So on 2026-08-19 nine handsets recorded a session in which the fleet was fully observable — ten
 * nodes, per-node capture rates, zero drops, all of it on a dashboard — and the phones were dark.
 * A degraded pose track, a stalled tracker or a clock that had quietly stopped syncing was
 * discoverable only by walking over to the participant and looking at their screen.
 *
 * IT SPEAKS OTLP DIRECTLY. THERE IS NO RELAY.
 * ------------------------------------------
 * The handset posts OTLP/HTTP JSON straight at the lab's collector, which terminates TLS itself and
 * checks a basic-auth credential. No application sits in between, and that is a decision rather than
 * a shortcut: a relay would have to re-encode every field, which means a second definition of every
 * metric that can drift from this one, plus a service that can be down while the measurement is up.
 *
 * It also buys something a relay cannot offer. Because this app writes `timeUnixNano` on every
 * sample, a handset that loses the network for five minutes and then flushes produces five real
 * minutes of history. An intermediary re-recording gauges would stamp them with its own clock, and
 * the gap would come back as a flat line and a step.
 *
 * The endpoint and credential come from the lab bundle ([TelemetrySink]), never from the binary — an
 * app binary is readable, so a compiled-in secret is a published secret.
 *
 * WHAT IT PROMISES, AND WHAT IT DELIBERATELY DOES NOT
 * --------------------------------------------------
 * It never blocks, never throws into the measurement, and never retries hard. The instrument is the
 * experiment and this is a courier: a courier that could fail a walk would be worse than no courier.
 * Every network call is wrapped, and a failure costs a log line.
 *
 * IT IS SILENT ABOUT THE HOST, NOT ABOUT ITSELF
 * ---------------------------------------------
 * An unconfigured deployment must not produce a phone retrying a host that was never meant to exist.
 * That reasoning was and is right, and the consequence was not: on 2026-08-26 a 21-minute survey
 * walk produced **zero** `{service_name="monad-app"}` lines in Loki while `log.tsv` uploaded
 * normally, and nothing anywhere said the courier had no endpoint. An unconfigured shipper looked
 * exactly like a working one, so the walk was unobservable until it uploaded — which is precisely
 * the case a lost upload cannot satisfy, and that walk lost its mesh.
 *
 * So [posture] is published, and the absence of an endpoint is stated **once** at start rather than
 * per flush. Silence about a host is correct; silence about the courier's own state is the failure.
 *
 * It also does not promise delivery. The buffer is capped at [MAX_BUFFERED]; when it overflows the
 * OLDEST sample is evicted and counted. That is the right direction for liveness — the newest sample
 * is the valuable one — and the count travels in the flush as `dropped`, so a gap is recorded rather
 * than hidden.
 *
 * The cap is TEN MINUTES on purpose, and it is not a memory decision. Mimir accepts a backdated
 * sample only inside its out-of-order window, which is ten minutes on this deployment. A sample older
 * than that could not be placed at the time it was taken, and a silently misplaced sample is worse
 * than one the shipper admits to dropping.
 */
class LabTelemetryShipper(
    private val instrument: LabInstrument,
    private val configService: LabConfigService,
) {

    /**
     * `Dispatchers.Default` rather than the `Dispatchers.IO` the rest of this package uses.
     *
     * Nothing here blocks a thread: the work is appending to a deque and awaiting a suspending Ktor
     * call, and the engine offloads its own socket IO. Holding an IO thread for the app's whole
     * lifetime to do that would cost a thread and buy nothing.
     *
     * (On Kotlin/Native `Dispatchers.IO` is an extension property, so using it here would also need
     * `import kotlinx.coroutines.IO` — which is why the neighbouring files carry that import.)
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val buffer = TelemetrySampleBuffer(MAX_BUFFERED)
    private val lock = Mutex()

    /**
     * What this courier is doing, for the console.
     *
     * Published rather than inferred from Loki, because the one question that could not be answered
     * on 2026-08-26 was "is the shipper even configured?" — and the answer lived only in a bundle
     * field nothing displayed.
     */
    private val _posture = MutableStateFlow(TelemetryPosture.UNKNOWN)
    val posture: StateFlow<TelemetryPosture> = _posture.asStateFlow()

    private var collectJob: Job? = null
    private var flushJob: Job? = null

    /**
     * The session the buffered samples belong to.
     *
     * Held here rather than read at flush time because a flush can land just after a session ended,
     * and filing those samples under "no session" would lose the last few seconds of every walk —
     * which is where a teardown failure lives.
     */
    private var sessionId: String? = null
    private var participant: String = ""
    private var site: String = ""

    /** Whether the last heartbeat was recording, so the end of a session can be noticed. */
    private var wasRecording = false

    /**
     * Begin observing. Idempotent, so the app may call it on every composition.
     *
     * Runs for the app's whole lifetime rather than per session, because the shipper must outlive
     * the console screen: a walk continues with the screen closed and the phone in a pocket, and a
     * screen-scoped courier would go silent at exactly the moment the participant stopped looking.
     */
    fun start() {
        if (collectJob != null) return

        // Announced ONCE, at start, and not per flush. A configured deployment gets one line naming
        // the endpoint; an unconfigured one gets one line saying so, which is the line whose absence
        // made a whole walk invisible on 2026-08-26. Repeating it per flush would be the retry noise
        // the silence rule exists to avoid.
        scope.launch {
            val sink = configService.config.value.telemetry
            _posture.value = TelemetryPosture(configured = sink.isConfigured, endpoint = sink.endpoint)
            if (sink.isConfigured) {
                Napier.i("[lab-telemetry] shipping to ${sink.endpoint} every ${sink.flushMillis / 1000} s")
            } else {
                Napier.w(
                    "[lab-telemetry] NOT CONFIGURED — the lab bundle carries no telemetry endpoint, " +
                        "so this handset is invisible on the dashboard until a session uploads. " +
                        "Refetch the bundle from the lab console."
                )
            }
        }

        collectJob = scope.launch {
            instrument.health.collect { health ->
                runCatching { onHeartbeat(health) }
                    .onFailure { Napier.w("[lab-telemetry] could not buffer a heartbeat: ${it.message}") }
            }
        }

        flushJob = scope.launch {
            while (true) {
                delay(configService.config.value.telemetry.flushMillis)
                runCatching { flush() }
                    .onFailure { Napier.w("[lab-telemetry] flush failed: ${it.message}") }
            }
        }
    }

    /**
     * Report one failed artefact-upload attempt, immediately rather than batched.
     *
     * [LabSessionUploader] already computes exactly why an attempt failed — HTTP status, response
     * content-type, timeout duration — because that is what it writes into `uploadError` locally. The
     * problem that detail never reached the operator until the session finally uploaded, which is
     * exactly the case a stuck upload cannot satisfy. This ships it the moment it happens, on the
     * existing `/v1/logs` path, the same one degraded stream samples already use.
     *
     * Best-effort like every other call in this class: a failed report must never turn one failed
     * upload into two failures worth caring about.
     */
    suspend fun reportUploadFailure(
        sessionId: String,
        participant: String,
        site: String,
        artefact: String,
        bytes: Int,
        attempt: Int,
        maxAttempts: Int,
        error: String?,
    ) {
        val sink = configService.config.value.telemetry
        if (!sink.isConfigured) return
        val request = TelemetryEncoder.uploadFailure(
            wallMs = currentTimeMillis(),
            sessionId = sessionId,
            participant = participant,
            site = site,
            appVersion = AppConfig.BUILD_ID,
            artefact = artefact,
            bytes = bytes,
            attempt = attempt,
            maxAttempts = maxAttempts,
            error = error,
        )
        runCatching { send(sink, LOGS_PATH, request) }
            .onFailure { Napier.w("[lab-telemetry] could not report upload failure for $artefact: ${it.message}") }
    }

    /** Stop observing and drop what is buffered. For tests and for a deliberate opt-out. */
    suspend fun stop() {
        collectJob?.cancel()
        flushJob?.cancel()
        collectJob = null
        flushJob = null
        lock.withLock {
            buffer.clear()
            wasRecording = false
        }
    }

    private suspend fun onHeartbeat(health: InstrumentHealth) {
        val state = instrument.state.value
        val session = state.sessionId

        // Nothing to say when no session is running. An idle app must not push, because a gauge that
        // keeps reporting the last walk's rates would read as a walk still in progress.
        if (session == null || !health.isRecording) {
            // The session just ended. Flush now rather than waiting out the interval, so the last
            // heartbeats — the ones describing teardown, which is where a failed stop lives — are
            // shipped while they still mean something.
            //
            // Detected here rather than hooked into the three `instrument.stop()` call sites, because
            // this also covers the paths that never reach one of them: the quest coordinator's own
            // teardown, and a session that died rather than stopped.
            if (wasRecording) {
                wasRecording = false
                runCatching { flush() }
                    .onFailure { Napier.w("[lab-telemetry] final flush failed: ${it.message}") }
            }
            return
        }
        wasRecording = true

        val sample = TelemetrySample(
            monoNs = monotonicNanos(),
            wallMs = currentTimeMillis(),
            elapsedMs = health.sessionElapsedMillis,
            phase = state.phase.name,
            overall = health.overall.wire,
            recording = health.isRecording,
            clockGate = health.clockGate.status.wire,
            streams = health.streams.map { it.toWire() },
            // Read straight off the instrument's own live readouts. Both are StateFlows it already
            // maintains for the console, so observing them adds no work to the measurement path.
            pose = instrument.poseProgress.value.takeIf { it.samples > 0 }?.let {
                TelemetryPose(
                    samples = it.samples,
                    normalFraction = it.normalFraction,
                    pathMetres = it.pathLengthMetres,
                    rejectedJumps = it.rejectedJumps,
                    pitchDegrees = it.pitchDegrees,
                    quality = it.quality.name.lowercase(),
                )
            },
            clock = instrument.clockEstimate.value.takeIf { it.samples > 0 }?.let {
                TelemetryClock(
                    offsetMillis = it.offsetMillis,
                    delayMillis = it.delayMillis,
                    skewPpm = it.skewPpm,
                    samples = it.samples,
                )
            },
        )

        lock.withLock {
            // A new session invalidates the old buffer: samples from two walks in one flush would be
            // filed under whichever session id happened to be current.
            if (sessionId != null && sessionId != session) {
                buffer.clear()
            }
            sessionId = session
            participant = state.request?.participantId.orEmpty()
            site = state.request?.site.orEmpty()

            buffer.add(sample)
        }
    }

    private suspend fun flush() {
        val sink = configService.config.value.telemetry
        if (!sink.isConfigured) {
            // No public collector in this deployment. Staying silent on the WIRE is correct: a bench
            // build retrying a host that was never meant to exist is pure noise, and the samples stay
            // buffered in case a bundle arrives mid-session. The posture is still published, because
            // that is a fact about this app rather than a request to a host.
            _posture.value = _posture.value.copy(configured = false, endpoint = "")
            return
        }
        if (!_posture.value.configured) {
            // A bundle arrived mid-run. Worth one line: the operator pressed Reload config and needs
            // to know whether it took.
            Napier.i("[lab-telemetry] endpoint appeared mid-run: ${sink.endpoint}")
            _posture.value = _posture.value.copy(configured = true, endpoint = sink.endpoint)
        }

        // Drained under the lock rather than copied, so the in-flight samples are not also sitting in
        // the buffer. Removing them afterwards by COUNT would be ambiguous: heartbeats keep arriving
        // during the request, and on a full buffer the eviction that makes room would already have
        // discarded the very samples the count was meant to remove.
        val payload = lock.withLock {
            val session = sessionId ?: return@withLock null
            if (buffer.isEmpty) return@withLock null
            val (samples, drops) = buffer.drain()
            TelemetryBatch(
                sessionId = session,
                participantToken = participant,
                site = site,
                platform = platformName(),
                appVersion = AppConfig.BUILD_ID,
                dropped = drops,
                samples = samples,
            )
        } ?: return

        try {
            // Metrics first. They carry the continuous signal, so if only one of the two requests can
            // get through, this is the one worth spending the connection on.
            send(sink, METRICS_PATH, TelemetryEncoder.metrics(payload))

            // Logs only when something was wrong. `null` means a clean stretch, and a clean stretch
            // needs no lines — the gauges already said so, more cheaply.
            TelemetryEncoder.logs(payload)?.let { send(sink, LOGS_PATH, it) }
        } catch (e: Throwable) {
            lock.withLock { buffer.restore(payload.samples, payload.dropped) }
            _posture.value = _posture.value.copy(
                configured = true,
                endpoint = sink.endpoint,
                lastError = e.message ?: e::class.simpleName ?: "unknown",
            )
            throw e
        }

        _posture.value = _posture.value.let {
            it.copy(
                configured = true,
                endpoint = sink.endpoint,
                flushes = it.flushes + 1,
                samplesShipped = it.samplesShipped + payload.samples.size,
                dropped = it.dropped + payload.dropped,
                lastFlushWallMillis = currentTimeMillis(),
                // lastError is NOT cleared. The bench question is "has this ever failed", and a
                // field one lucky flush wipes cannot answer it.
                lastError = it.lastError,
            )
        }
    }

    /**
     * One OTLP/HTTP JSON request.
     *
     * The URL is absolute, which deliberately bypasses [KtorClient]'s `defaultRequest` base URL: this
     * does not go to the API host. Everything else about that client is wanted — the JSON negotiation,
     * the timeout, and `expectSuccess = true`, which turns a 401 from the collector into an exception
     * the caller can put the samples back after, rather than a silent success.
     */
    private suspend fun send(sink: TelemetrySink, path: String, body: Any) {
        KtorClient.client.post(sink.endpoint.trimEnd('/') + path) {
            headers { append(HttpHeaders.Authorization, basicAuth(sink)) }
            setBody(body)
        }
    }

    /**
     * `Basic base64(user:password)`.
     *
     * Encoded by hand because kotlin's stdlib base64 is experimental on the targets this builds for,
     * and a courier is not worth an opt-in annotation on the whole module. The alphabet is fixed and
     * the credential is ASCII, so this is a dozen lines rather than a dependency.
     */
    private fun basicAuth(sink: TelemetrySink): String =
        "Basic " + base64("${sink.username}:${sink.password}".encodeToByteArray())

    private fun platformName(): String = when {
        Platform.isIOS -> "ios"
        Platform.isAndroid -> "android"
        else -> "unknown"
    }

    companion object {
        /** The two OTLP/HTTP signal paths. Fixed by the protocol, not by this deployment. */
        const val METRICS_PATH = "/v1/metrics"
        const val LOGS_PATH = "/v1/logs"

        /**
         * Buffered samples before the oldest is evicted. Ten minutes at 1 Hz.
         *
         * Bounded by Mimir's out-of-order window rather than by memory: a sample older than that
         * window cannot be stored at the time it was taken, and the shipper would be silently
         * misplacing history instead of admitting a gap. See the class doc.
         */
        const val MAX_BUFFERED = 600
    }
}

private fun StreamHealth.toWire(): TelemetryStream = TelemetryStream(
    stream = stream.name.lowercase(),
    state = state.wire,
    worstState = worstState.wire,
    rateHz = eventsPerSecond,
    expectedRateHz = expectedRateHz,
    totalEvents = totalEvents,
    silenceMs = silenceMillis,
    deliveredFraction = deliveredFraction,
    troubleMs = troubleMillis,
)

/**
 * The wire contract for `POST /api/lab/telemetry`.
 *
 * snake_case, matching the ground-truth surface rather than the camelCase quest DTOs: the field
 * names are read by an operator in a Loki query, so they should look like the metric labels they
 * become.
 */
@Serializable
internal data class TelemetryBatch(
    @SerialName("session_id") val sessionId: String,
    @SerialName("participant_token") val participantToken: String,
    @SerialName("site") val site: String,
    @SerialName("platform") val platform: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("dropped") val dropped: Int,
    @SerialName("samples") val samples: List<TelemetrySample>,
)

@Serializable
internal data class TelemetrySample(
    @SerialName("mono_ns") val monoNs: Long,
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("elapsed_ms") val elapsedMs: Long,
    @SerialName("phase") val phase: String,
    @SerialName("overall") val overall: String,
    @SerialName("recording") val recording: Boolean,
    @SerialName("clock_gate") val clockGate: String,
    @SerialName("streams") val streams: List<TelemetryStream>,
    /**
     * Capture QUALITY, as opposed to capture liveness.
     *
     * The stream list answers "is data arriving at the commanded pace". These answer "is the data
     * any good", and they are different questions with different failure modes: odometry does not
     * stop when it fails, it degrades, so a pose stream can be perfectly ALIVE at 10 Hz while every
     * sample it produces is geometrically worthless. Null when the session does not play the role.
     */
    @SerialName("pose") val pose: TelemetryPose? = null,
    @SerialName("clock") val clock: TelemetryClock? = null,
)

/**
 * Pose-track quality. Every field is one the instrument already maintains for the console
 * (`PoseTrackProgress`) — this ships them, it does not compute anything new.
 */
@Serializable
internal data class TelemetryPose(
    @SerialName("samples") val samples: Long,
    /**
     * Fraction of samples the platform itself called NORMAL. The single best answer to "is this
     * walk usable"; null before the first sample, which is not the same as zero.
     */
    @SerialName("normal_fraction") val normalFraction: Double?,
    @SerialName("path_metres") val pathMetres: Double,
    /**
     * Relocalisation jumps rejected from the path length. `PoseTrackProgress` calls a rising count
     * "the live signal that the tracker is not holding, which is actionable while the operator is
     * still in the room" — so it is exactly what belongs on a dashboard during a walk.
     */
    @SerialName("rejected_jumps") val rejectedJumps: Long,
    /**
     * Smoothed camera pitch, degrees. Carried because pitch EXPLAINS tracking quality rather than
     * merely correlating with it: a phone held pointing at the floor starves ARKit of features, and
     * the 2026-08-19 sessions differed by −39° versus −14°.
     */
    @SerialName("pitch_degrees") val pitchDegrees: Double?,
    @SerialName("quality") val quality: String,
)

/**
 * Clock discipline. The gate that decides whether anything else this session recorded can be
 * joined to a fleet capture at all — a walk with no clock sample is a trajectory on a device-local
 * timeline nobody else shares.
 */
@Serializable
internal data class TelemetryClock(
    @SerialName("offset_ms") val offsetMillis: Double,
    @SerialName("delay_ms") val delayMillis: Double,
    @SerialName("skew_ppm") val skewPpm: Double,
    @SerialName("samples") val samples: Int,
)

@Serializable
internal data class TelemetryStream(
    @SerialName("stream") val stream: String,
    @SerialName("state") val state: String,
    @SerialName("worst_state") val worstState: String,
    @SerialName("rate_hz") val rateHz: Double,
    @SerialName("expected_rate_hz") val expectedRateHz: Double?,
    @SerialName("total_events") val totalEvents: Long,
    @SerialName("silence_ms") val silenceMs: Long,
    @SerialName("delivered_fraction") val deliveredFraction: Double?,
    @SerialName("trouble_ms") val troubleMs: Long,
)

/**
 * The shipper's bounded sample queue.
 *
 * Extracted from [LabTelemetryShipper] for the same reason `StreamHealthTracker` is a pure function
 * of (counter, time): the interesting behaviour is not the HTTP call, it is what happens to a walk's
 * samples when the network is gone for ten minutes — and that is worth testing exhaustively, without
 * a socket, a Koin graph or an instrument in scope.
 *
 * Not thread-safe by design. The shipper owns the only instance and guards it with a mutex, which
 * keeps the locking in one place rather than spread across two objects that could disagree.
 */
internal class TelemetrySampleBuffer(private val capacity: Int) {

    private val samples = ArrayDeque<TelemetrySample>()

    /** Samples evicted un-shipped since the last successful drain. Reported, never swallowed. */
    var dropped: Int = 0
        private set

    val size: Int get() = samples.size

    val isEmpty: Boolean get() = samples.isEmpty()

    /**
     * Append one sample, evicting the OLDEST if that overflows the cap.
     *
     * Oldest-first is the right direction for liveness: the newest sample is the one that answers
     * "is this walk healthy right now", so a full buffer must never cost it in order to keep history.
     */
    fun add(sample: TelemetrySample) {
        samples.addLast(sample)
        trim()
    }

    /** Take everything, leaving the buffer empty. The drop count travels with the batch. */
    fun drain(): Pair<List<TelemetrySample>, Int> {
        val taken = samples.toList()
        val drops = dropped
        samples.clear()
        dropped = 0
        return taken to drops
    }

    /**
     * Put a failed batch back at the FRONT, ahead of whatever arrived meanwhile.
     *
     * Front, so the buffer stays in time order and the next attempt retries oldest-first. Then the
     * cap is re-applied: if the restore pushed it over, the oldest go and are counted — exactly as
     * they would have been had the drain never happened.
     */
    fun restore(batch: List<TelemetrySample>, drops: Int) {
        samples.addAll(0, batch)
        dropped += drops
        trim()
    }

    fun clear() {
        samples.clear()
        dropped = 0
    }

    private fun trim() {
        while (samples.size > capacity) {
            samples.removeFirst()
            ++dropped
        }
    }
}

/**
 * Standard base64, no line breaks. Enough for a `user:password` credential.
 *
 * Hand-rolled because `kotlin.io.encoding.Base64` still needs `@OptIn(ExperimentalEncodingApi::class)`
 * on the targets this module builds for, and adding an experimental opt-in to ship one header is a
 * worse trade than fourteen lines with a fixed alphabet.
 */
private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private fun base64(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var index = 0
    while (index + 2 < bytes.size) {
        val chunk = (bytes[index].toInt() and 0xFF shl 16) or
            (bytes[index + 1].toInt() and 0xFF shl 8) or
            (bytes[index + 2].toInt() and 0xFF)
        out.append(BASE64_ALPHABET[chunk shr 18 and 0x3F])
        out.append(BASE64_ALPHABET[chunk shr 12 and 0x3F])
        out.append(BASE64_ALPHABET[chunk shr 6 and 0x3F])
        out.append(BASE64_ALPHABET[chunk and 0x3F])
        index += 3
    }
    // The tail: one or two leftover bytes are padded with '=' so the length stays a multiple of four.
    when (bytes.size - index) {
        1 -> {
            val chunk = bytes[index].toInt() and 0xFF shl 16
            out.append(BASE64_ALPHABET[chunk shr 18 and 0x3F])
            out.append(BASE64_ALPHABET[chunk shr 12 and 0x3F])
            out.append("==")
        }
        2 -> {
            val chunk = (bytes[index].toInt() and 0xFF shl 16) or (bytes[index + 1].toInt() and 0xFF shl 8)
            out.append(BASE64_ALPHABET[chunk shr 18 and 0x3F])
            out.append(BASE64_ALPHABET[chunk shr 12 and 0x3F])
            out.append(BASE64_ALPHABET[chunk shr 6 and 0x3F])
            out.append('=')
        }
    }
    return out.toString()
}
