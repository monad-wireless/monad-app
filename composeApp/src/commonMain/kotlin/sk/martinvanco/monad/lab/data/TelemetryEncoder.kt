package sk.martinvanco.monad.lab.data

/**
 * Turns buffered instrument heartbeats into OTLP payloads.
 *
 * Pure — no clock, no network, no Koin — for the same reason `StreamHealthTracker` is a pure function
 * of (counter, time): the interesting behaviour is what the label sets and timestamps come out as,
 * and that deserves exhaustive tests rather than an integration run against a live collector.
 *
 * WHAT IS A LABEL AND WHAT IS NOT
 * ------------------------------
 * The rule that matters, because the collector keeps series for a year:
 *
 *   metric labels  = site, platform, participant, stream   — all bounded sets
 *   log attributes = the above plus session id, build id, phase, clock gate, per-stream detail
 *
 * `participant` is in the metric labels because nine handsets run at once, and without a per-phone
 * dimension they would all write the same series from independent gauges — one phone's health would
 * silently be reported as the room's. It is safe to use because it is the stable backend user id, so
 * it bounds at the number of enrolled people and does not grow with time.
 *
 * `session_id` is NOT a metric label. It is new for every walk, so it would mint a fresh time series
 * per session against a 365-day retention. It travels as a log attribute, where cardinality is free.
 *
 * WHY `state` IS A NUMBER AND NOT A LABEL
 * --------------------------------------
 * A gauge whose label set changes leaves the old series holding its last value forever. Putting
 * `state` on `rate_hz` would mean a stream that went ALIVE -> DEAD kept publishing its healthy rate
 * under the ALIVE series indefinitely, which reads as a working stream. So state ships as its own
 * numeric gauge, where a transition moves one series instead of stranding one.
 */
internal object TelemetryEncoder {

    /** Metric names. `monad.lab.*` matches what the backend's session-sidecar metrics already use. */
    const val RATE_HZ = "monad.lab.stream.rate_hz"
    const val DELIVERED = "monad.lab.stream.delivered"
    const val SEVERITY = "monad.lab.stream.severity"
    const val SILENCE_MS = "monad.lab.stream.silence_ms"
    const val TROUBLE_MS = "monad.lab.stream.trouble_ms"
    const val EVENTS = "monad.lab.stream.events"
    const val SESSION_SEVERITY = "monad.lab.session.severity"
    const val SESSION_RECORDING = "monad.lab.session.recording"
    const val SESSION_ELAPSED_MS = "monad.lab.session.elapsed_ms"
    const val DROPPED = "monad.lab.telemetry.dropped"

    // ── Capture quality, as distinct from capture liveness ───────────────────────
    // The stream metrics above say data is arriving at the commanded pace. These say
    // whether it is worth anything. A pose stream ALIVE at 10 Hz whose normal-fraction
    // is 0.2 is the exact failure this pair exists to make visible.
    const val POSE_NORMAL_FRACTION = "monad.lab.pose.normal_fraction"
    const val POSE_REJECTED_JUMPS = "monad.lab.pose.rejected_jumps"
    const val POSE_PATH_METRES = "monad.lab.pose.path_metres"
    const val POSE_PITCH_DEGREES = "monad.lab.pose.pitch_degrees"
    const val CLOCK_OFFSET_MS = "monad.lab.clock.offset_ms"
    const val CLOCK_DELAY_MS = "monad.lab.clock.delay_ms"
    const val CLOCK_SKEW_PPM = "monad.lab.clock.skew_ppm"
    const val CLOCK_SAMPLES = "monad.lab.clock.samples"

    /**
     * Every metric name this app may emit.
     *
     * Mirrored by the collector's own allow-list filter, and the mirror is the containment: the
     * credential that reaches this endpoint travels to participant handsets, so it must be assumed
     * public. Authentication decides who may knock; this list decides what can possibly get in.
     */
    val METRIC_NAMES: Set<String> = setOf(
        RATE_HZ, DELIVERED, SEVERITY, SILENCE_MS, TROUBLE_MS, EVENTS,
        SESSION_SEVERITY, SESSION_RECORDING, SESSION_ELAPSED_MS, DROPPED,
        POSE_NORMAL_FRACTION, POSE_REJECTED_JUMPS, POSE_PATH_METRES, POSE_PITCH_DEGREES,
        CLOCK_OFFSET_MS, CLOCK_DELAY_MS, CLOCK_SKEW_PPM, CLOCK_SAMPLES,
    )

    fun metrics(batch: TelemetryBatch): OtlpMetricsRequest {
        val scope = listOf(
            Otlp.attribute("site", batch.site),
            Otlp.attribute("platform", batch.platform),
            Otlp.attribute("participant", batch.participantToken),
        )

        // One data point per (metric, sample), each stamped with the sample's OWN wall clock. This is
        // the whole reason the app speaks OTLP itself: a five-minute network gap comes back as five
        // real minutes of history rather than one late value.
        val rateHz = mutableListOf<OtlpNumberDataPoint>()
        val delivered = mutableListOf<OtlpNumberDataPoint>()
        val severity = mutableListOf<OtlpNumberDataPoint>()
        val silence = mutableListOf<OtlpNumberDataPoint>()
        val trouble = mutableListOf<OtlpNumberDataPoint>()
        val events = mutableListOf<OtlpNumberDataPoint>()
        val sessionSeverity = mutableListOf<OtlpNumberDataPoint>()
        val sessionRecording = mutableListOf<OtlpNumberDataPoint>()
        val sessionElapsed = mutableListOf<OtlpNumberDataPoint>()
        val poseNormal = mutableListOf<OtlpNumberDataPoint>()
        val poseJumps = mutableListOf<OtlpNumberDataPoint>()
        val posePath = mutableListOf<OtlpNumberDataPoint>()
        val posePitch = mutableListOf<OtlpNumberDataPoint>()
        val clockOffset = mutableListOf<OtlpNumberDataPoint>()
        val clockDelay = mutableListOf<OtlpNumberDataPoint>()
        val clockSkew = mutableListOf<OtlpNumberDataPoint>()
        val clockSamples = mutableListOf<OtlpNumberDataPoint>()

        for (sample in batch.samples) {
            val at = Otlp.nanosOf(sample.wallMs)

            sessionSeverity += OtlpNumberDataPoint(at, severityOf(sample.overall), scope)
            sessionRecording += OtlpNumberDataPoint(at, if (sample.recording) 1.0 else 0.0, scope)
            sessionElapsed += OtlpNumberDataPoint(at, sample.elapsedMs.toDouble(), scope)

            // Quality blocks are absent when the session does not play the role, and absent is not
            // zero: a witness-only participant has no pose track, and reporting normal_fraction 0
            // for them would be a broken tracker that does not exist.
            sample.pose?.let { pose ->
                pose.normalFraction?.let { poseNormal += OtlpNumberDataPoint(at, it, scope) }
                pose.pitchDegrees?.let { posePitch += OtlpNumberDataPoint(at, it, scope) }
                poseJumps += OtlpNumberDataPoint(at, pose.rejectedJumps.toDouble(), scope)
                posePath += OtlpNumberDataPoint(at, pose.pathMetres, scope)
            }
            sample.clock?.let { clock ->
                // Absolute offset: the gate is on magnitude, and a dashboard threshold at 100 ms
                // would otherwise be passed by a −400 ms sample.
                clockOffset += OtlpNumberDataPoint(at, kotlin.math.abs(clock.offsetMillis), scope)
                clockDelay += OtlpNumberDataPoint(at, clock.delayMillis, scope)
                clockSkew += OtlpNumberDataPoint(at, clock.skewPpm, scope)
                clockSamples += OtlpNumberDataPoint(at, clock.samples.toDouble(), scope)
            }

            for (stream in sample.streams) {
                val attributes = scope + Otlp.attribute("stream", stream.stream)

                severity += OtlpNumberDataPoint(at, severityOf(stream.state), attributes)
                rateHz += OtlpNumberDataPoint(at, stream.rateHz, attributes)
                silence += OtlpNumberDataPoint(at, stream.silenceMs.toDouble(), attributes)
                trouble += OtlpNumberDataPoint(at, stream.troubleMs.toDouble(), attributes)
                events += OtlpNumberDataPoint(at, stream.totalEvents.toDouble(), attributes)

                // Only the paced streams have a commanded rate. Recording 0 for an event-driven
                // stream would claim it delivered nothing, when it was never told to deliver a rate.
                stream.deliveredFraction?.let {
                    delivered += OtlpNumberDataPoint(at, it, attributes)
                }
            }
        }

        // The drop count rides on the newest sample's timestamp, so a gap is visible at the moment it
        // was noticed rather than at the start of the buffer.
        val dropped = batch.samples.lastOrNull()?.let {
            listOf(OtlpNumberDataPoint(Otlp.nanosOf(it.wallMs), batch.dropped.toDouble(), scope))
        }.orEmpty()

        val metrics = listOfNotNull(
            gauge(SEVERITY, "1", severity),
            gauge(RATE_HZ, "Hz", rateHz),
            gauge(DELIVERED, "1", delivered),
            gauge(SILENCE_MS, "ms", silence),
            gauge(TROUBLE_MS, "ms", trouble),
            gauge(EVENTS, "events", events),
            gauge(SESSION_SEVERITY, "1", sessionSeverity),
            gauge(SESSION_RECORDING, "1", sessionRecording),
            gauge(SESSION_ELAPSED_MS, "ms", sessionElapsed),
            gauge(DROPPED, "samples", dropped),
            gauge(POSE_NORMAL_FRACTION, "1", poseNormal),
            gauge(POSE_REJECTED_JUMPS, "jumps", poseJumps),
            gauge(POSE_PATH_METRES, "m", posePath),
            gauge(POSE_PITCH_DEGREES, "deg", posePitch),
            gauge(CLOCK_OFFSET_MS, "ms", clockOffset),
            gauge(CLOCK_DELAY_MS, "ms", clockDelay),
            gauge(CLOCK_SKEW_PPM, "ppm", clockSkew),
            gauge(CLOCK_SAMPLES, "samples", clockSamples),
        )

        return OtlpMetricsRequest(
            resourceMetrics = listOf(
                OtlpResourceMetrics(
                    resource = resource(batch),
                    scopeMetrics = listOf(OtlpScopeMetrics(OtlpScope(Otlp.SCOPE), metrics)),
                )
            )
        )
    }

    /**
     * Log records for the samples worth reading.
     *
     * Only the unhealthy ones, plus nothing else. A clean walk at 1 Hz for forty minutes is 2,400
     * lines that all say "fine", and the gauges already say that more cheaply. The same policy the
     * collector already applies to `cypher.match`: drop the fast and successful, keep what is worth
     * seeing. Returns null when there is nothing to say, so the shipper can skip the request entirely.
     */
    fun logs(batch: TelemetryBatch): OtlpLogsRequest? {
        val records = batch.samples
            .filter { severityOf(it.overall) >= DEGRADED_SEVERITY }
            .map { sample ->
                val troubled = sample.streams
                    .filter { severityOf(it.state) >= DEGRADED_SEVERITY }

                OtlpLogRecord(
                    timeUnixNano = Otlp.nanosOf(sample.wallMs),
                    severityNumber = Otlp.SEVERITY_WARN,
                    severityText = "WARN",
                    body = OtlpAnyValue(
                        "instrument degraded: " + troubled.joinToString(", ") {
                            "${it.stream}=${it.state}@${format1(it.rateHz)}Hz"
                        }.ifBlank { sample.overall }
                    ),
                    attributes = listOf(
                        Otlp.attribute("site", batch.site),
                        Otlp.attribute("platform", batch.platform),
                        Otlp.attribute("participant", batch.participantToken),
                        // Free here, forbidden as a metric label. This is where a specific walk is
                        // identifiable months later.
                        Otlp.attribute("session_id", batch.sessionId),
                        Otlp.attribute("build_id", batch.appVersion),
                        Otlp.attribute("phase", sample.phase),
                        Otlp.attribute("overall", sample.overall),
                        Otlp.attribute("clock_gate", sample.clockGate),
                        // The monotonic stamp is the axis every other artefact of this session was
                        // written on, so a log line can be lined up against `pose.tsv` even if the
                        // handset's wall clock stepped mid-walk.
                        Otlp.attribute("mono_ns", sample.monoNs.toString()),
                        Otlp.attribute("elapsed_ms", sample.elapsedMs.toString()),
                        Otlp.attribute("troubled_streams", troubled.joinToString(",") { it.stream }),
                    ),
                )
            }

        if (records.isEmpty()) return null

        return OtlpLogsRequest(
            resourceLogs = listOf(
                OtlpResourceLogs(
                    resource = resource(batch),
                    scopeLogs = listOf(OtlpScopeLogs(OtlpScope(Otlp.SCOPE), records)),
                )
            )
        )
    }

    /**
     * `service.name` is what Loki's `service_name` label and Mimir's `target_info` are derived from,
     * so it is the one string that decides whether this shows up beside `csid` and `api` or nowhere.
     */
    private fun resource(batch: TelemetryBatch): OtlpResource = resource(batch.appVersion)

    private fun resource(appVersion: String): OtlpResource = OtlpResource(
        listOf(
            Otlp.attribute("service.name", SERVICE_NAME),
            Otlp.attribute("service.version", appVersion),
            Otlp.attribute("monad.node_role", "participant-handset"),
        )
    )

    /**
     * One log record for an artefact upload attempt that did NOT succeed.
     *
     * The gap this closes: [LabSessionUploader] already knows exactly why an upload failed — HTTP
     * status, response content-type, timeout duration, all of it — because that is what
     * `uploadError` stores locally. Until now that detail reached the operator only once the session
     * finally uploaded (chicken-and-egg for a session that cannot upload), or by walking over and
     * reading the console. This ships it live, the same way a degraded stream already does.
     *
     * Success is never logged here, for the same reason a clean stream heartbeat is never logged: an
     * hour of "mesh.ply attempt 1/1 ok" is pure noise next to `monad_lab_upload_bytes_sum`, which
     * already says that more cheaply.
     */
    fun uploadFailure(
        wallMs: Long,
        sessionId: String,
        participant: String,
        site: String,
        appVersion: String,
        artefact: String,
        bytes: Int,
        attempt: Int,
        maxAttempts: Int,
        error: String?,
    ): OtlpLogsRequest {
        val record = OtlpLogRecord(
            timeUnixNano = Otlp.nanosOf(wallMs),
            severityNumber = Otlp.SEVERITY_ERROR,
            severityText = "ERROR",
            body = OtlpAnyValue("upload failed: $artefact ($bytes bytes, attempt $attempt/$maxAttempts): ${error ?: "unknown error"}"),
            attributes = listOf(
                Otlp.attribute("site", site),
                Otlp.attribute("participant", participant),
                Otlp.attribute("session_id", sessionId),
                Otlp.attribute("build_id", appVersion),
                Otlp.attribute("artefact", artefact),
                Otlp.attribute("bytes", bytes.toString()),
                Otlp.attribute("attempt", attempt.toString()),
                Otlp.attribute("max_attempts", maxAttempts.toString()),
                Otlp.attribute("error", error ?: "unknown error"),
            ),
        )

        return OtlpLogsRequest(
            resourceLogs = listOf(
                OtlpResourceLogs(
                    resource = resource(appVersion),
                    scopeLogs = listOf(OtlpScopeLogs(OtlpScope(Otlp.SCOPE), listOf(record))),
                )
            )
        )
    }

    /**
     * One log record for a session the process did not survive.
     *
     * THE GAP THIS CLOSES. On 2026-09-04 a walk ended when FrontBoard killed the app with
     * `0x8BADF00D`. Server-side, the only trace was a gauge that stopped being written — which is
     * exactly what a phone that walked out of Wi-Fi range looks like, and what a walk that simply
     * ended looks like once the five-minute staleness window passes. Three different facts, one
     * appearance.
     *
     * A crash cannot report itself, so this is reported by the launch that finds the wreckage:
     * `LabSessionRecovery` already computes the reason and the last moment the session was
     * observably alive, and both travel here. `last_alive_wall_ms` is the checkpoint's own time
     * rather than the recovery's, so the record dates the death and not the discovery.
     *
     * `severityNumber` is ERROR rather than WARN: a degraded stream is a walk worth less, a killed
     * process is a walk that has to be repeated.
     */
    fun sessionInterrupted(
        wallMs: Long,
        sessionId: String,
        participant: String,
        site: String,
        appVersion: String,
        reason: String,
        lastAliveWallMs: Long,
        rows: Long,
    ): OtlpLogsRequest {
        val record = OtlpLogRecord(
            timeUnixNano = Otlp.nanosOf(wallMs),
            severityNumber = Otlp.SEVERITY_ERROR,
            severityText = "ERROR",
            body = OtlpAnyValue(
                "session interrupted: $rows row(s) recovered on a later launch, last alive at " +
                    "$lastAliveWallMs — $reason"
            ),
            attributes = listOf(
                Otlp.attribute("site", site),
                Otlp.attribute("participant", participant),
                Otlp.attribute("session_id", sessionId),
                // The build that OPENED the session, not the one recovering it. A crashed session is
                // not a random sample of sessions, so its provenance is the one worth keeping.
                Otlp.attribute("build_id", appVersion),
                Otlp.attribute("reason", reason),
                Otlp.attribute("last_alive_wall_ms", lastAliveWallMs.toString()),
                Otlp.attribute("rows", rows.toString()),
            ),
        )

        return OtlpLogsRequest(
            resourceLogs = listOf(
                OtlpResourceLogs(
                    resource = resource(appVersion),
                    scopeLogs = listOf(OtlpScopeLogs(OtlpScope(Otlp.SCOPE), listOf(record))),
                )
            )
        )
    }

    private fun gauge(name: String, unit: String, points: List<OtlpNumberDataPoint>): OtlpMetric? =
        if (points.isEmpty()) null else OtlpMetric(name, unit, OtlpGauge(points))

    /**
     * The app's `StreamState.severity`, restated over the wire form.
     *
     * Restated rather than imported so this file stays free of the domain: the encoder is given
     * already-flattened wire strings, which is what makes it testable from a literal.
     */
    private fun severityOf(state: String): Double = when (state) {
        "not_applicable" -> 0.0
        "alive" -> 0.0
        "idle" -> 1.0
        "degraded" -> 2.0
        "stale" -> 3.0
        "dead" -> 4.0
        else -> 0.0
    }

    /** One decimal place, without depending on a platform formatter. */
    private fun format1(value: Double): String {
        val scaled = (value * 10.0).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }

    const val SERVICE_NAME = "monad-app"

    /** `degraded` and worse. The threshold the app's own `isHealthy` uses. */
    private const val DEGRADED_SEVERITY = 2.0
}
