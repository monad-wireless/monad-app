package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.core.domain.wifi_v2.WifiConnectionServiceV2
import sk.martinvanco.monad.core.domain.wifi_v2.WifiSecurityType
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.lab.data.LabSessionRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The instrument: one object that assembles the roles a phone plays into a single session.
 *
 * The order of [start] is the order of the EXP-P3 gates, and it is deliberate:
 *
 * 1. **Residency** — acquire background residency first. If the process will not survive
 *    backgrounding there is nothing worth measuring, and on iOS the same location session is what
 *    keeps the emitter alive, so this is not an optional preamble.
 * 2. **Association** — join the commanded AP.
 * 3. **Pinning** — open the socket *pinned to that interface*, and record whether pinning actually
 *    took. An unpinned socket is the design's worst failure mode: the UI says connected, the
 *    datagrams leave over cellular, and the observer sees nothing.
 * 4. **Clock** — discipline against the collector before any data is stamped.
 * 5. **Emission and witnessing** — only now.
 *
 * A failure at any step aborts with a reason rather than degrading silently. The session record is
 * still written, because a session that failed to start is evidence too.
 */
@OptIn(ExperimentalUuidApi::class)
class LabInstrument(
    private val socket: LabDatagramSocket,
    private val clockSync: ClockSyncService,
    private val trafficGenerator: TrafficGenerator,
    private val beaconWitness: BeaconWitness,
    private val residency: BackgroundResidency,
    private val wifi: WifiConnectionServiceV2,
    private val repository: LabSessionRepository,
    private val environment: LabEnvironment,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private var scope: CoroutineScope? = null
    private var witnessJob: Job? = null
    private var resyncJob: Job? = null
    private var zoneTracker: ZoneTracker? = null

    private val _state = MutableStateFlow(LabInstrumentState.IDLE)
    val state: StateFlow<LabInstrumentState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<InstrumentLogLine>>(emptyList())
    val log: StateFlow<List<InstrumentLogLine>> = _log.asStateFlow()

    val trafficStats: StateFlow<TrafficStats> get() = trafficGenerator.stats
    val clockEstimate: StateFlow<ClockEstimate> get() = clockSync.estimate

    private var sessionEvents = mutableListOf<SessionEvent>()
    private val pendingTraffic = mutableListOf<TrafficSample>()

    /**
     * Bring the instrument up for one session.
     *
     * [emit] false runs a witness-only session (no association, no socket) — the mode a passive
     * participant carries during a field run where only zone truth is wanted.
     */
    suspend fun start(request: SessionRequest): Result<String> {
        if (_state.value.phase != Phase.IDLE) {
            return Result.failure(IllegalStateException("instrument already running"))
        }

        val sessionUuid = Uuid.random()
        val sessionId = sessionUuid.toString()
        val sessionBytes = sessionUuid.toByteArray()
        val startedMono = monotonicNanos()
        val startedWall = currentTimeMillis()
        sessionEvents = mutableListOf()
        pendingTraffic.clear()

        _state.value = LabInstrumentState.IDLE.copy(sessionId = sessionId, phase = Phase.STARTING)
        note("session $sessionId starting")

        // 1 — residency before anything else.
        val residencyResult = residency.acquire("MonadCount lab session")
        if (residencyResult.isFailure) {
            val why = residencyResult.exceptionOrNull()?.message ?: "unknown"
            fail("background residency refused: $why")
            return Result.failure(IllegalStateException("residency: $why"))
        }
        event("residency_acquired")
        note("residency acquired")

        // 2 — association (illuminator sessions only).
        var boundDescription = "n/a"
        var pinned = false
        if (request.emit) {
            val ap = request.accessPoint
            if (ap != null && ap.ssid.isNotBlank()) {
                _state.value = _state.value.copy(phase = Phase.ASSOCIATING)
                val security = when (ap.security.lowercase()) {
                    "wpa3" -> WifiSecurityType.WPA3
                    "wep" -> WifiSecurityType.WEP
                    "open" -> WifiSecurityType.OPEN
                    else -> WifiSecurityType.WPA2
                }
                val joined = wifi.connect(ap.ssid, ap.password, security)
                if (joined.isFailure) {
                    val why = joined.exceptionOrNull()?.message ?: "unknown"
                    fail("association to ${ap.ssid} failed: $why")
                    return Result.failure(IllegalStateException("association: $why"))
                }
                event("associated", ap.ssid)
                note("associated to ${ap.ssid}")
            } else {
                note("no AP commanded — using the current network")
            }

            // 3 — pin the socket, and be loud about whether pinning took.
            _state.value = _state.value.copy(phase = Phase.BINDING)
            val opened = socket.open(
                host = request.collector.host,
                port = request.collector.udpPort,
                interfaceHint = environment.wifiInterfaceHint,
            )
            if (opened.isFailure) {
                val why = opened.exceptionOrNull()?.message ?: "unknown"
                fail("socket open failed: $why")
                return Result.failure(IllegalStateException("socket: $why"))
            }
            boundDescription = socket.boundInterfaceDescription()
            pinned = boundDescription.isNotBlank() && !boundDescription.contains("unpinned")
            event("socket_open", boundDescription)
            note(if (pinned) "socket pinned: $boundDescription" else "SOCKET NOT PINNED ($boundDescription)")
        }

        repository.open(
            sessionId = sessionId,
            participantId = request.participantId,
            enrollmentId = request.enrollmentId,
            questId = request.questId,
            site = request.site,
            apId = request.accessPoint?.id,
            profileId = request.trafficProfile?.id,
            startedWallMillis = startedWall,
            startedMonotonicNanos = startedMono,
            boundInterface = boundDescription,
            socketPinned = pinned,
        )

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope

        // 4 — clock, before any sample is stamped.
        if (request.emit) {
            _state.value = _state.value.copy(phase = Phase.SYNCING)
            announce(sessionBytes, request)
            val burst = clockSync.runBurst(sessionBytes, request.clockSync)
            burst.onSuccess {
                repository.appendClock(sessionId, it)
                event("clock_sync", "offset=${it.offsetMillis}ms rtt=${it.delayMillis}ms")
                note("clock offset ${format(it.offsetMillis)} ms (rtt ${format(it.delayMillis)} ms)")
            }.onFailure {
                // Not fatal: a session with an undisciplined clock is still worth recording, as
                // long as the sidecar says so plainly.
                event("clock_sync_failed", it.message ?: "")
                note("clock sync FAILED — timestamps are intervals of unknown width")
            }

            resyncJob = newScope.launch {
                val period = request.clockSync.resyncSeconds.coerceAtLeast(30) * 1000L
                while (isActive) {
                    delay(period)
                    // Re-announce with every burst: a collector restarted mid-session, or one that
                    // missed the opening datagram on a contended channel, otherwise files the rest
                    // of the session under an unknown participant.
                    announce(sessionBytes, request)
                    clockSync.runBurst(sessionBytes, request.clockSync).onSuccess {
                        repository.appendClock(sessionId, it)
                    }
                }
            }
        }

        // 5 — witnessing.
        if (request.witness && request.beacons.isConfigured) {
            val tracker = ZoneTracker(request.beacons)
            zoneTracker = tracker
            val started = beaconWitness.start(request.beacons)
            if (started.isFailure) {
                note("witness refused: ${started.exceptionOrNull()?.message}")
                event("witness_failed", started.exceptionOrNull()?.message ?: "")
            } else {
                note("witnessing ${request.beacons.zones.size} anchors")
                witnessJob = beaconWitness.observations
                    .onEach { observation ->
                        repository.appendBeacon(sessionId, observation)
                        tracker.observe(observation)?.let { transition ->
                            repository.appendTransition(sessionId, transition)
                            note("zone ${if (transition.entered) "ENTER" else "EXIT"} ${transition.zone?.label ?: transition.major.toString() + "/" + transition.minor}")
                        }
                        _state.value = _state.value.copy(
                            beaconCount = _state.value.beaconCount + 1,
                            lastRssi = observation.rssi,
                            currentZones = tracker.currentZones(),
                        )
                    }
                    .launchIn(newScope)

                beaconWitness.transitions
                    .onEach { repository.appendTransition(sessionId, it) }
                    .launchIn(newScope)

                newScope.launch {
                    while (isActive) {
                        delay(5_000)
                        tracker.sweep(currentTimeMillis(), monotonicNanos()).forEach {
                            repository.appendTransition(sessionId, it)
                        }
                        _state.value = _state.value.copy(currentZones = tracker.currentZones())
                    }
                }
            }
        }

        // 6 — emission.
        if (request.emit) {
            val profile = request.trafficProfile
            if (profile != null) {
                val emitted = trafficGenerator.start(sessionBytes, profile) { sample ->
                    pendingTraffic += sample
                    if (pendingTraffic.size >= TRAFFIC_BATCH) {
                        val batch = pendingTraffic.toList()
                        pendingTraffic.clear()
                        newScope.launch { repository.appendTraffic(sessionId, batch) }
                    }
                }
                if (emitted.isFailure) {
                    note("emission refused: ${emitted.exceptionOrNull()?.message}")
                } else {
                    event("emission_started", "${profile.rateHz} Hz × ${profile.durationSeconds}s")
                    note("emitting ${profile.rateHz} Hz for ${profile.durationSeconds} s")
                }
            }
        }

        _state.value = _state.value.copy(
            phase = Phase.RUNNING,
            startedWallMillis = startedWall,
            startedMonotonicNanos = startedMono,
            boundInterface = boundDescription,
            socketPinned = pinned,
            request = request,
        )
        return Result.success(sessionId)
    }

    /** Stop everything, write the sidecar, and leave the data on disk for the uploader. */
    suspend fun stop(): Result<String> {
        val sessionId = _state.value.sessionId ?: return Result.failure(IllegalStateException("no session"))
        val request = _state.value.request

        trafficGenerator.stop()
        beaconWitness.stop()
        witnessJob?.cancel()
        resyncJob?.cancel()
        scope?.cancel()
        scope = null
        witnessJob = null
        resyncJob = null

        if (pendingTraffic.isNotEmpty()) {
            repository.appendTraffic(sessionId, pendingTraffic.toList())
            pendingTraffic.clear()
        }

        socket.close()
        residency.release()
        event("session_closed")

        val endedMono = monotonicNanos()
        val endedWall = currentTimeMillis()
        val stats = trafficGenerator.stats.value
        val clock = clockSync.estimate.value
        val counts = repository.counts(sessionId)

        val sidecar = LabSessionSidecar(
            identity = SessionIdentity(
                sessionId = sessionId,
                participantId = request?.participantId ?: "",
                enrollmentId = request?.enrollmentId ?: "",
                questId = request?.questId ?: "",
                site = request?.site ?: "",
                configVersion = request?.configVersion ?: 0,
                roles = buildList {
                    if (request?.emit == true) add(LabRole.ILLUMINATOR.name.lowercase())
                    if (request?.witness == true) add(LabRole.WITNESS.name.lowercase())
                    add(LabRole.SUBJECT.name.lowercase())
                },
            ),
            radio = SessionRadio(
                apId = request?.accessPoint?.id ?: "",
                ssid = request?.accessPoint?.ssid ?: "",
                band = request?.accessPoint?.band ?: "",
                channel = request?.accessPoint?.channel,
                collectorHost = request?.collector?.host ?: "",
                collectorPort = request?.collector?.udpPort ?: 0,
                boundInterface = _state.value.boundInterface,
                socketPinned = _state.value.socketPinned,
                beaconUuid = request?.beacons?.uuid ?: "",
            ),
            environment = SessionEnvironment(
                platform = environment.platform,
                osVersion = environment.osVersion,
                deviceModel = environment.deviceModel,
                appVersion = environment.appVersion,
                clockSource = clockSourceName(),
                bootId = clockBootId(),
                timezone = environment.timezone,
                residencyChecks = residency.diagnostics().map {
                    "${it.name}=${if (it.satisfied) "ok" else "MISSING"} (${it.detail})"
                },
            ),
            lifecycle = SessionLifecycle(
                startedWallMillis = _state.value.startedWallMillis,
                endedWallMillis = endedWall,
                startedMonotonicNanos = _state.value.startedMonotonicNanos,
                endedMonotonicNanos = endedMono,
                status = SessionStatus.CLOSED.storageKey,
                events = sessionEvents.toList(),
            ),
            summary = SessionSummary(
                commandedRateHz = stats.commandedRateHz,
                achievedRateHz = stats.achievedRateHz,
                packetsSent = stats.sent,
                packetsFailed = stats.failed,
                intervalCv = stats.intervalCv,
                maxGapMillis = stats.maxGapMillis,
                beaconObservations = counts.beacons,
                zoneTransitions = counts.transitions,
                clockOffsetMillis = clock.offsetMillis,
                clockDelayMillis = clock.delayMillis,
                clockSkewPpm = clock.skewPpm,
            ),
            clockSamples = clockSync.history.value.map {
                ClockSampleRecord(it.anchorNanos, it.offsetNanos, it.delayNanos, it.skewPpm, it.samples)
            },
        )

        val encoded = json.encodeToString(LabSessionSidecar.serializer(), sidecar)
        repository.close(sessionId, endedWall, endedMono, encoded)
        _state.value = LabInstrumentState.IDLE
        zoneTracker = null
        note("session $sessionId closed — ${stats.sent} packets, ${counts.beacons} beacon rows")
        return Result.success(sessionId)
    }

    /**
     * Tell the collector who this session belongs to.
     *
     * Fire-and-forget by design: it is repeated, and a session that never gets one is filed under
     * `unknown-participant` rather than lost — so a failure here must not abort a run.
     */
    private fun announce(sessionId: ByteArray, request: SessionRequest) {
        val hello = SessionHello(
            participantId = request.participantId,
            site = request.site,
            apId = request.accessPoint?.id ?: "",
            platform = environment.platform,
            appVersion = environment.appVersion,
            commandedRateHz = request.trafficProfile?.rateHz ?: 0.0,
        )
        val payload = json.encodeToString(SessionHello.serializer(), hello).encodeToByteArray()
        val packet = LabPacket.encode(
            type = LabPacket.TYPE_SESSION_HELLO,
            sessionId = sessionId,
            sequence = 0,
            monotonicNanos = monotonicNanos(),
            wallMillis = currentTimeMillis(),
            payload = payload,
        )
        socket.send(packet).onFailure {
            note("session announcement not sent: ${it.message}")
        }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private fun note(message: String) {
        Napier.i("[lab] $message")
        _log.value = (_log.value + InstrumentLogLine(currentTimeMillis(), message)).takeLast(MAX_LOG)
    }

    private fun event(kind: String, detail: String = "") {
        sessionEvents += SessionEvent(monotonicNanos(), currentTimeMillis(), kind, detail)
    }

    private suspend fun fail(message: String) {
        note(message)
        event("start_failed", message)
        trafficGenerator.stop()
        beaconWitness.stop()
        socket.close()
        residency.release()
        _state.value = LabInstrumentState.IDLE.copy(lastError = message)
    }

    private fun format(value: Double): String {
        val scaled = (value * 1000).toLong()
        return "${scaled / 1000}.${(scaled % 1000).toString().padStart(3, '0').trimEnd('0').ifEmpty { "0" }}"
    }

    private companion object {
        const val MAX_LOG = 200
        const val TRAFFIC_BATCH = 250
    }
}

/** Everything the instrument needs to run one session. Built from [LabConfig] by the caller. */
data class SessionRequest(
    val participantId: String,
    val collector: CollectorEndpoint,
    val beacons: BeaconPlan = BeaconPlan(),
    val accessPoint: ApProfile? = null,
    val trafficProfile: TrafficProfile? = null,
    val clockSync: ClockSyncPolicy = ClockSyncPolicy(),
    val site: String = "",
    val configVersion: Int = 0,
    val enrollmentId: String? = null,
    val questId: String? = null,
    /** Play the illuminator role: associate, pin, sync, emit. */
    val emit: Boolean = true,
    /** Play the witness role: monitor and range the anchors. */
    val witness: Boolean = true,
)

enum class Phase { IDLE, STARTING, ASSOCIATING, BINDING, SYNCING, RUNNING }

data class LabInstrumentState(
    val sessionId: String?,
    val phase: Phase,
    val startedWallMillis: Long,
    val startedMonotonicNanos: Long,
    val boundInterface: String,
    val socketPinned: Boolean,
    val beaconCount: Long,
    val lastRssi: Int?,
    val currentZones: List<String>,
    val lastError: String?,
    val request: SessionRequest?,
) {
    val isRunning: Boolean get() = phase == Phase.RUNNING

    companion object {
        val IDLE = LabInstrumentState(
            sessionId = null,
            phase = Phase.IDLE,
            startedWallMillis = 0,
            startedMonotonicNanos = 0,
            boundInterface = "",
            socketPinned = false,
            beaconCount = 0,
            lastRssi = null,
            currentZones = emptyList(),
            lastError = null,
            request = null,
        )
    }
}

data class InstrumentLogLine(val wallMillis: Long, val message: String)

/** Platform facts recorded into every sidecar. */
expect class LabEnvironment() {
    val platform: String
    val osVersion: String
    val deviceModel: String
    val timezone: String

    /** Interface name the datagram socket should pin to (`en0` on iOS; unused on Android). */
    val wifiInterfaceHint: String?
}

/** App version, kept out of [LabEnvironment] so the expect/actual surface stays platform-only. */
val LabEnvironment.appVersion: String get() = AppConfig.APP_VERSION
