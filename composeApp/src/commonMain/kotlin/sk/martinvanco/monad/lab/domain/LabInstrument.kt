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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import sk.martinvanco.monad.core.domain.wifi.WifiConnectionService
import sk.martinvanco.monad.core.domain.wifi.WifiSecurityType
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.lab.domain.health.InstrumentHealth
import sk.martinvanco.monad.lab.domain.health.LabStream
import sk.martinvanco.monad.lab.domain.health.SessionHealthMonitor
import sk.martinvanco.monad.lab.domain.health.StreamCounters
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
    private val wifi: WifiConnectionService,
    private val repository: SessionRecorder,
    private val environment: LabEnvironment,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private var scope: CoroutineScope? = null
    private var witnessJob: Job? = null
    private var resyncJob: Job? = null
    private var heartbeatJob: Job? = null
    private var zoneTracker: ZoneTracker? = null

    private val _state = MutableStateFlow(LabInstrumentState.IDLE)
    val state: StateFlow<LabInstrumentState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<InstrumentLogLine>>(emptyList())
    val log: StateFlow<List<InstrumentLogLine>> = _log.asStateFlow()

    /**
     * Per-stream liveness, refreshed on a 1 Hz heartbeat.
     *
     * Driven by **polling the counters the instrument already maintains**, never by a callback
     * inside the send loop. The illuminator's pacing is the measurement; it does not acquire work
     * on behalf of a status display.
     */
    private val _health = MutableStateFlow(InstrumentHealth.IDLE)
    val health: StateFlow<InstrumentHealth> = _health.asStateFlow()

    /**
     * Which experimental block is open, and what the last one was.
     *
     * Held by the instrument rather than by the console screen model so it survives the screen
     * being closed: an operator who backs out of the console mid-plateau must not lose the block.
     */
    private val _blocks = MutableStateFlow(BlockSessionState.EMPTY)
    val blocks: StateFlow<BlockSessionState> = _blocks.asStateFlow()

    val trafficStats: StateFlow<TrafficStats> get() = trafficGenerator.stats
    val clockEstimate: StateFlow<ClockEstimate> get() = clockSync.estimate

    private var sessionEvents = mutableListOf<SessionEvent>()
    private val pendingTraffic = mutableListOf<TrafficSample>()

    private var healthMonitor: SessionHealthMonitor? = null
    private var transitionCount: Long = 0
    private var groundTruthCount: Long = 0
    private var sessionBootId: String = ""
    private var lastCheckpointMillis: Long = 0
    private var healthCheckpoints: Long = 0

    /** The running session's id as wire bytes, kept so a boundary sync needs no re-parse. */
    private var sessionIdBytes: ByteArray = ByteArray(0)

    /**
     * Told, not asked: a ground-truth scan was written while this recording was open.
     *
     * The scan itself belongs to the **scanned** lab session, not to this recording, so the
     * instrument does not own it and must not try to. All it wants is the count, so the health
     * panel can say "the people channel is alive" rather than leaving the one stream nobody can
     * derive from radio silently unmonitored.
     */
    fun noteGroundTruthScan(count: Int = 1) {
        groundTruthCount += count.coerceAtLeast(0)
    }

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
        sessionIdBytes = sessionBytes
        val startedMono = monotonicNanos()
        val startedWall = currentTimeMillis()
        sessionEvents = mutableListOf()
        pendingTraffic.clear()
        transitionCount = 0
        groundTruthCount = 0
        healthCheckpoints = 0
        lastCheckpointMillis = 0
        _blocks.value = BlockSessionState.EMPTY
        // Captured once, at the top, and carried on the session row. Every sample in this session
        // is stamped on the monotonic clock of this epoch and on no other; recovery after a restart
        // compares against it rather than assuming continuity.
        sessionBootId = clockBootId()
        clockSync.reset()
        // Same class of leak: TrafficGenerator.stop() keeps the final counts for summarising, and
        // start() only re-initialises them inside the coroutine it launches — which a witness-only
        // session never runs. Reset here so a non-emitting session cannot inherit the previous
        // session's emission numbers into its sidecar.
        trafficGenerator.reset()

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
            bootId = sessionBootId,
        )

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope

        // 4 — clock, before any sample is stamped.
        if (request.emit) {
            _state.value = _state.value.copy(phase = Phase.SYNCING)
            announce(sessionBytes, request)
            runClockBurst(sessionId, sessionBytes, request, opening = true)

            resyncJob = newScope.launch {
                val period = request.clockSync.resyncSeconds.coerceAtLeast(30) * 1000L
                // Gate G4 fits `unix_ts_ns ≈ a·mono_ns + b` per recording session and needs **two**
                // samples before the skew term is identifiable at all; with one it degrades to
                // offset-only and the fold is flagged. At the default 600 s resync a session under
                // ten minutes long would ship exactly one sample and be quietly downgraded. So the
                // second burst is pulled forward to a minute in, and the cadence settles afterwards.
                val secondBurstDelay = period.coerceAtMost(SECOND_BURST_MILLIS)
                delay(secondBurstDelay)
                if (isActive) {
                    announce(sessionBytes, request)
                    runClockBurst(sessionId, sessionBytes, request, opening = false)
                }
                while (isActive) {
                    delay(period)
                    // Re-announce with every burst: a collector restarted mid-session, or one that
                    // missed the opening datagram on a contended channel, otherwise files the rest
                    // of the session under an unknown participant.
                    announce(sessionBytes, request)
                    runClockBurst(sessionId, sessionBytes, request, opening = false)
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
                            transitionCount++
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
                    .onEach {
                        repository.appendTransition(sessionId, it)
                        transitionCount++
                    }
                    .launchIn(newScope)

                newScope.launch {
                    while (isActive) {
                        delay(5_000)
                        tracker.sweep(currentTimeMillis(), monotonicNanos()).forEach {
                            repository.appendTransition(sessionId, it)
                            transitionCount++
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

        // Health last, so its clock starts when the session is genuinely running and the streams
        // are not judged against a warm-up they never had.
        val monitor = SessionHealthMonitor.forSession(
            emitting = request.emit && request.trafficProfile != null,
            commandedRateHz = request.trafficProfile?.rateHz ?: 0.0,
            witnessing = request.witness && request.beacons.isConfigured,
            resyncSeconds = request.clockSync.resyncSeconds,
            startedAtMillis = monotonicNanos() / 1_000_000,
        )
        healthMonitor = monitor
        newScope.startHeartbeat(monitor, emitting = request.emit)

        return Result.success(sessionId)
    }

    /** Stop everything, write the sidecar, and leave the data on disk for the uploader. */
    /**
     * Label the running session's timeline.
     *
     * Silently ignored when no session is running: a step completing after the instrument aborted
     * is not an error worth surfacing to a participant, and losing a marker is strictly better than
     * crashing a run over one.
     */
    suspend fun mark(
        kind: SessionMarker.Kind,
        label: String,
        stepId: String? = null,
        payload: String? = null,
    ) {
        val sessionId = _state.value.sessionId?.takeIf { it.isNotEmpty() } ?: return
        val marker = SessionMarker(
            kind = kind,
            label = label,
            stepId = stepId,
            payload = payload,
            monotonicNanos = monotonicNanos(),
            wallMillis = currentTimeMillis(),
        )
        runCatching { repository.appendMarker(sessionId, marker) }
            .onFailure { Napier.w("[lab] marker dropped: ${it.message}") }
        note("mark ${kind.wire}: $label")
    }

    // ---- experimental blocks ----------------------------------------------------------------

    /**
     * Open an experimental block.
     *
     * The order here is the whole precision story, and it is deliberate:
     *
     * 1. **Read the clock first.** `mono_ns` and `wall_ms` are captured at the instant of the tap,
     *    before any network work. The boundary therefore never pays for the sync that follows it —
     *    a block edge is exact on the device timeline whatever the radio is doing.
     * 2. **Then sync, briefly and optionally.** A short burst is fired only if the standing estimate
     *    is older than [BOUNDARY_SYNC_FRESHNESS_MILLIS], and the whole attempt is capped at
     *    [BOUNDARY_SYNC_BUDGET_MILLIS]. It puts a sync anchor within a fraction of a second of every
     *    block boundary, which is where the precision is actually needed: cycling ramps are 30 s and
     *    are T3's primary event set, so these edges are held to gate **G4b (250 ms)**, not G4a.
     * 3. **Then write, always.** The marker is written whether or not the burst succeeded, from the
     *    stamp captured in step 1, into SQLite. Precision must not depend on the network being up at
     *    the moment of the mark — the phone is usually on an experiment AP with no route anywhere.
     *
     * A refusal ([BlockCommandResult.Rejected]) writes nothing at all.
     */
    suspend fun startBlock(
        request: BlockStartRequest,
        tally: BlockTally? = null,
    ): BlockCommandResult = commandBlock(tally) { state, mono, wall, clock ->
        BlockMachine.start(
            state = state,
            request = request,
            sessionRunning = _state.value.isRunning,
            monotonicNanos = mono,
            wallMillis = wall,
            tally = tally,
            clock = clock,
        )
    }

    /** Close the running block. See [startBlock] for the ordering that keeps the edge exact. */
    suspend fun stopBlock(
        tally: BlockTally? = null,
        reason: BlockStopReason = BlockStopReason.OPERATOR,
    ): BlockCommandResult = commandBlock(tally) { state, mono, wall, clock ->
        BlockMachine.stop(
            state = state,
            reason = reason,
            monotonicNanos = mono,
            wallMillis = wall,
            tally = tally,
            clock = clock,
        )
    }

    private suspend fun commandBlock(
        tally: BlockTally?,
        decide: (BlockSessionState, Long, Long, ClockStamp?) -> BlockCommandResult,
    ): BlockCommandResult {
        // 1 — the boundary, captured before anything can delay it.
        val mono = monotonicNanos()
        val wall = currentTimeMillis()

        val sessionId = _state.value.sessionId?.takeIf { it.isNotEmpty() }
        if (sessionId == null || !_state.value.isRunning) {
            // Decide anyway, so the refusal is produced by the state machine and not duplicated
            // here — but nothing is written, because there is no stream to label.
            return decide(_blocks.value, mono, wall, null)
        }

        // 2 — a short, capped, best-effort sync so the edge is mapped with a fresh estimate.
        refreshBoundarySync(sessionId)

        val clock = clockStampAt(mono)
        val result = decide(_blocks.value, mono, wall, clock)
        if (result is BlockCommandResult.Applied) {
            _blocks.value = result.state
            // 3 — local first, always.
            result.marks.forEach { writeBlockMark(sessionId, it) }
        } else if (result is BlockCommandResult.Rejected) {
            note("block refused: ${result.reason.message}")
        }
        return result
    }

    /**
     * The best estimate this device can make of how well [monotonicNanos] maps onto the collector
     * timeline, right now.
     *
     * Recomputed per marker rather than reused from the health heartbeat: sync quality changes
     * within a session, and a boundary that was marked just after a failed burst must not inherit
     * the verdict of one marked just after a good one.
     */
    fun clockStampAt(monotonicNanos: Long): ClockStamp = ClockGate.stamp(
        atMonotonicNanos = monotonicNanos,
        samples = clockSync.history.value,
        applicable = _state.value.request?.emit == true,
    )

    /**
     * A compact sync burst at a block boundary.
     *
     * Cheap on purpose: eight exchanges 5 ms apart with a short per-exchange timeout, skipped
     * entirely when the standing estimate is already fresh, and hard-capped so a dead link costs a
     * fraction of a second rather than the participant's patience. Failure is silent by design —
     * the block marker is written either way, and a boundary marked without a fresh sync simply
     * carries a larger `sync_age_ms`, which the analysis can see.
     */
    private suspend fun refreshBoundarySync(sessionId: String) {
        val request = _state.value.request ?: return
        if (!request.emit) return
        val newest = clockSync.history.value.lastOrNull()
        if (newest != null) {
            val ageMillis = (monotonicNanos() - newest.anchorNanos) / 1_000_000L
            if (ageMillis in 0 until BOUNDARY_SYNC_FRESHNESS_MILLIS) return
        }
        val sessionBytes = sessionIdBytes.takeIf { it.isNotEmpty() } ?: return
        val policy = request.clockSync.copy(
            burstSize = BOUNDARY_BURST_SIZE,
            burstSpacingMs = BOUNDARY_BURST_SPACING_MILLIS,
            timeoutMs = BOUNDARY_EXCHANGE_TIMEOUT_MILLIS,
        )
        runCatching {
            withTimeoutOrNull(BOUNDARY_SYNC_BUDGET_MILLIS) {
                runClockBurst(sessionId, sessionBytes, request.copy(clockSync = policy), opening = false)
            }
        }.onFailure { Napier.w("[lab] boundary sync skipped: ${it.message}") }
    }

    private suspend fun writeBlockMark(sessionId: String, mark: BlockMark) {
        val payload = BlockMarkerPayload.encode(
            BlockMarkerPayload.of(
                mark = mark,
                // On the operator's handset the lab session IS this recording — the printed
                // check-in code is anchored to it — so both ids are the same value here, and both
                // are written so that equality stays a checkable fact rather than an assumption.
                labSessionId = sessionId,
                recordingSessionId = sessionId,
            )
        )
        runCatching {
            repository.appendMarker(
                sessionId,
                SessionMarker(
                    kind = mark.markerKind,
                    label = mark.label,
                    // block_id as a first-class column: a reader that only wants block boundaries
                    // never has to parse the payload.
                    stepId = mark.blockId,
                    payload = payload,
                    monotonicNanos = mark.monotonicNanos,
                    wallMillis = mark.wallMillis,
                ),
            )
        }.onFailure { Napier.w("[lab] block marker dropped: ${it.message}") }
        note("block ${mark.label}")
        mark.warnings.forEach { note("  ! ${it.message}") }
    }

    suspend fun stop(): Result<String> {
        val sessionId = _state.value.sessionId ?: return Result.failure(IllegalStateException("no session"))
        val request = _state.value.request

        // A block left open at session end is closed here, on the same clock reading as the rest of
        // the shutdown, and the marker records *that it was closed automatically*. Leaving it open
        // would give the analysis a block with a leading edge and no trailing one, which it can only
        // resolve by guessing.
        //
        // The fact is remembered for the sidecar: the edge is real, but it is the session stopping
        // rather than the operator judging the condition over, and the session report says so.
        val blockOpenAtSessionEnd = _blocks.value.isRunning
        if (blockOpenAtSessionEnd) {
            stopBlock(reason = BlockStopReason.SESSION_END)
        }

        // One last heartbeat *before* the generators are told to stop, so the health record
        // describes the session as it ran rather than the instant after everything was shut down.
        val finalHealth = healthMonitor?.tick(
            counters = StreamCounters(
                illuminator = trafficGenerator.stats.value.sent,
                witness = _state.value.beaconCount,
                transitions = transitionCount,
                groundTruth = groundTruthCount,
                clock = clockSync.history.value.size.toLong(),
            ),
            nowMillis = monotonicNanos() / 1_000_000,
            clockGate = ClockGate.evaluate(
                clockSync.history.value,
                applicable = request?.emit == true,
            ),
            isRecording = false,
        ) ?: InstrumentHealth.IDLE

        trafficGenerator.stop()
        beaconWitness.stop()
        witnessJob?.cancel()
        resyncJob?.cancel()
        heartbeatJob?.cancel()
        scope?.cancel()
        scope = null
        witnessJob = null
        resyncJob = null
        heartbeatJob = null
        healthMonitor = null

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
                buildId = environment.buildId,
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
                bootId = sessionBootId,
                // A clean stop() is by definition inside the epoch the session opened in — the
                // process has been alive throughout. Recovery is the path that can say otherwise.
                monotonicContinuous = true,
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
                markers = counts.markers,
                blocks = counts.blocks,
                blockOpenAtSessionEnd = blockOpenAtSessionEnd,
                healthCheckpoints = counts.health,
                clockOffsetMillis = clock.offsetMillis,
                clockDelayMillis = clock.delayMillis,
                clockSkewPpm = clock.skewPpm,
            ),
            clockSamples = clockSync.history.value.map {
                ClockSampleRecord(it.anchorNanos, it.offsetNanos, it.delayNanos, it.skewPpm, it.samples)
            },
            health = finalHealth.toRecords(),
            clockGate = finalHealth.clockGate.toRecord(),
        )

        val encoded = json.encodeToString(LabSessionSidecar.serializer(), sidecar)
        repository.close(sessionId, endedWall, endedMono, encoded)
        _state.value = LabInstrumentState.IDLE
        _health.value = InstrumentHealth.IDLE
        _blocks.value = BlockSessionState.EMPTY
        sessionIdBytes = ByteArray(0)
        zoneTracker = null
        note("session $sessionId closed — ${stats.sent} packets, ${counts.beacons} beacon rows, "
            + "${counts.markers} markers (${counts.blocks} block edges), ${counts.clock} clock "
            + "samples, ${counts.health} health checkpoints")
        finalHealth.everTroubled.forEach {
            note(
                "${it.stream.label}: worst state ${it.worstState.wire} for " +
                    "${SessionReport.formatDuration(it.troubleMillis)}"
            )
        }
        if (finalHealth.clockGate.wouldFailGate) {
            note("CLOCK GATE G4 WOULD FAIL — ${finalHealth.clockGate.headline}")
        }
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

    /**
     * One clock burst: exchange, persist, and leave a marker on the timeline.
     *
     * The marker matters as much as the sample. Gate G4's residual is measured at **sync markers**,
     * and the pre-registration asks for at least four per fold; a burst that writes only into
     * `clock.tsv` gives the analysis an offset but no anchor on the shared stream to test it at.
     * Emitting one marker per successful burst makes the marker budget a consequence of the resync
     * cadence rather than an operator's memory.
     */
    private suspend fun runClockBurst(
        sessionId: String,
        sessionBytes: ByteArray,
        request: SessionRequest,
        opening: Boolean,
    ) {
        clockSync.runBurst(sessionBytes, request.clockSync)
            .onSuccess { estimate ->
                repository.appendClock(sessionId, estimate)
                runCatching {
                    repository.appendMarker(
                        sessionId,
                        SessionMarker(
                            kind = SessionMarker.Kind.CLOCK_SYNC,
                            label = "clock burst",
                            payload = "{\"offset_ns\":${estimate.offsetNanos}," +
                                "\"rtt_ns\":${estimate.delayNanos}," +
                                "\"skew_ppm\":${estimate.skewPpm}," +
                                "\"exchanges\":${estimate.samples}}",
                            monotonicNanos = estimate.anchorNanos,
                            wallMillis = currentTimeMillis(),
                        ),
                    )
                }.onFailure { Napier.w("[lab] clock marker dropped: ${it.message}") }
                if (opening) {
                    event("clock_sync", "offset=${estimate.offsetMillis}ms rtt=${estimate.delayMillis}ms")
                    note("clock offset ${format(estimate.offsetMillis)} ms (rtt ${format(estimate.delayMillis)} ms)")
                }
            }
            .onFailure {
                // Not fatal: a session with an undisciplined clock is still worth recording, as
                // long as the sidecar says so plainly — and now the health panel says so while the
                // operator is still in the room.
                event("clock_sync_failed", it.message ?: "")
                if (opening) note("clock sync FAILED — timestamps are intervals of unknown width")
            }
    }

    /**
     * The 1 Hz heartbeat that turns counters into health.
     *
     * One coroutine for the whole instrument, reading values the other paths already keep. It never
     * writes to the streams and never touches the socket, so the worst it can do is report stale
     * numbers.
     */
    private fun CoroutineScope.startHeartbeat(monitor: SessionHealthMonitor, emitting: Boolean) {
        heartbeatJob = launch {
            while (isActive) {
                val nowNanos = monotonicNanos()
                val now = nowNanos / 1_000_000
                val snapshot = monitor.tick(
                    counters = StreamCounters(
                        illuminator = trafficGenerator.stats.value.sent,
                        witness = _state.value.beaconCount,
                        transitions = transitionCount,
                        groundTruth = groundTruthCount,
                        clock = clockSync.history.value.size.toLong(),
                    ),
                    nowMillis = now,
                    clockGate = ClockGate.evaluate(clockSync.history.value, applicable = emitting),
                    isRecording = _state.value.isRunning,
                )
                _health.value = snapshot
                checkpointHealth(snapshot, nowNanos, now)
                delay(HEARTBEAT_MILLIS)
            }
        }
    }

    /**
     * Persist the health picture, at most once per [CHECKPOINT_MILLIS].
     *
     * Health used to live only in memory, so a session the OS killed came back through recovery
     * with row counts and no liveness story at all — and a crashed session is not a random sample of
     * sessions, it is a biased sample of the ones that went wrong. Writing it down periodically is
     * what makes "was it degraded for 42 minutes?" answerable for those.
     *
     * The write happens on the heartbeat's own coroutine, off counters that were already being
     * polled for the display. **Nothing on the emission path changed**: `TrafficGenerator` and
     * `BeaconWitness` are untouched, health is still polled rather than pushed, and the illuminator's
     * send loop has gained no work.
     */
    private suspend fun checkpointHealth(
        health: InstrumentHealth,
        nowNanos: Long,
        nowMillis: Long,
    ) {
        if (!_state.value.isRunning) return
        if (lastCheckpointMillis != 0L && nowMillis - lastCheckpointMillis < CHECKPOINT_MILLIS) return
        val sessionId = _state.value.sessionId?.takeIf { it.isNotEmpty() } ?: return
        lastCheckpointMillis = nowMillis
        runCatching {
            repository.appendHealthCheckpoint(
                sessionId = sessionId,
                monotonicNanos = nowNanos,
                wallMillis = currentTimeMillis(),
                health = health,
            )
        }
            .onSuccess { healthCheckpoints++ }
            .onFailure { Napier.w("[lab] health checkpoint dropped: ${it.message}") }
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
        heartbeatJob?.cancel()
        heartbeatJob = null
        healthMonitor = null
        socket.close()
        residency.release()
        _state.value = LabInstrumentState.IDLE.copy(lastError = message)
        _health.value = InstrumentHealth.IDLE
    }

    private fun format(value: Double): String {
        val scaled = (value * 1000).toLong()
        return "${scaled / 1000}.${(scaled % 1000).toString().padStart(3, '0').trimEnd('0').ifEmpty { "0" }}"
    }

    private companion object {
        const val MAX_LOG = 200
        const val TRAFFIC_BATCH = 250

        /** Health refresh. One second is the resolution a person reads "last event 4 s ago" at. */
        const val HEARTBEAT_MILLIS = 1_000L

        /** How soon after the opening burst the second one fires. See gate G4 in [ClockGate]. */
        const val SECOND_BURST_MILLIS = 60_000L

        /**
         * How often health is written down.
         *
         * Thirty seconds is the resolution at which "it was degraded from 02:14 to 02:56" is a
         * useful sentence, and it costs one small transaction of five rows per half-minute — about
         * 600 rows over a three-hour session, against the ~2 million traffic rows beside them.
         */
        const val CHECKPOINT_MILLIS = 30_000L

        /**
         * A standing estimate younger than this is good enough for a block boundary, and the
         * boundary sync is skipped.
         *
         * Fifteen seconds: at a typical handset skew of a few tens of ppm, fifteen seconds of drift
         * is well under a millisecond, so a fresher burst would buy nothing measurable against
         * G4b's 250 ms budget while costing the operator a wait at every block edge.
         */
        const val BOUNDARY_SYNC_FRESHNESS_MILLIS = 15_000L

        /** Exchanges in a boundary burst. Enough for the minimum-delay filter to have a choice. */
        const val BOUNDARY_BURST_SIZE = 8

        const val BOUNDARY_BURST_SPACING_MILLIS = 5L

        const val BOUNDARY_EXCHANGE_TIMEOUT_MILLIS = 150L

        /**
         * Hard cap on a boundary sync.
         *
         * On a dead link every exchange times out, and eight of those would cost over a second. The
         * cap keeps a boundary mark bounded: the stamp was already taken, so the only thing this
         * delays is the SQLite write, and the marker goes in either way.
         */
        const val BOUNDARY_SYNC_BUDGET_MILLIS = 400L
    }
}

/** Projection of live health into the serialisable sidecar block. */
private fun InstrumentHealth.toRecords(): List<StreamHealthRecord> = streams.map { stream ->
    StreamHealthRecord(
        stream = stream.stream.name.lowercase(),
        state = stream.state.wire,
        worst = stream.worstState.wire,
        events = stream.totalEvents,
        eventsPerSecond = stream.eventsPerSecond,
        expectedRateHz = stream.expectedRateHz,
        deliveredFraction = stream.deliveredFraction,
        silenceMillis = stream.silenceMillis,
        degradedMillis = stream.millisDegraded,
        staleMillis = stream.millisStale,
        deadMillis = stream.millisDead,
    )
}

private fun ClockGateReport.toRecord(): ClockGateRecord = ClockGateRecord(
    status = status.wire,
    samples = sampleCount,
    meetsMinimumSamples = meetsMinimumSamples,
    skewPpm = skewPpm,
    offsetMillis = offsetMillis,
    fitA = fit?.a,
    fitBNanos = fit?.bNanos,
    fitSpanMillis = fit?.spanMillis,
    maxFitResidualMillis = maxFitResidualMillis,
    wouldFailGate = wouldFailGate,
    note = note,
)

// `SessionRequest`, `Phase`, `LabInstrumentState` and `InstrumentLogLine` live in
// `LabInstrumentState.kt`; `LabEnvironment` (expect) in `LabEnvironment.kt`, beside its actuals.
