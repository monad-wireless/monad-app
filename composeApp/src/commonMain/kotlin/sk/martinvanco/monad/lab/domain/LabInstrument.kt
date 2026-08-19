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
    private val broadcaster: IdentityBroadcaster,
    private val poseTracker: PoseTracker,
    private val referenceClock: ReferenceClock,
    private val wake: ForegroundWake,
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
    private var broadcastStopJob: Job? = null
    private var broadcastReport: BroadcastReport? = null
    private var broadcastActive: Boolean = false
    private var poseJob: Job? = null
    private var poseFlushJob: Job? = null
    private var meshJob: Job? = null
    private var meshRevisions: Long = 0
    private var poseReport: PoseTrackReport? = null
    private var poseCount: Long = 0

    /**
     * The most recent pose, held so a waypoint scan can be stamped with a position synchronously.
     *
     * A waypoint's value is the *pair* of a printed code and a position, and the operator taps at
     * the moment the two coincide. Waiting on the next pose from the flow would put up to one sample
     * period between them, and reading it back out of SQLite would put a disk round trip there.
     */
    private var lastPose: PoseSample? = null

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

    /**
     * The pose track as it accumulates.
     *
     * Held here, not in the console's screen model, so an operator who backs out of the console
     * mid-walk and comes back does not find the trajectory readout reset to zero — the same reason
     * [blocks] lives here.
     */
    private val _poseProgress = MutableStateFlow(PoseTrackProgress.IDLE)
    val poseProgress: StateFlow<PoseTrackProgress> = _poseProgress.asStateFlow()

    /**
     * What the room scan has found so far.
     *
     * The one thing an operator can act on while still in the room: a mesh that has stopped growing
     * halfway through a walk means the LiDAR is looking at something it cannot resolve — a window, a
     * dark corridor, a surface past its range — and the fix is to walk it again more slowly.
     */
    private val _mesh = MutableStateFlow(MeshProgress.IDLE)
    val meshProgress: StateFlow<MeshProgress> = _mesh.asStateFlow()

    val trafficStats: StateFlow<TrafficStats> get() = trafficGenerator.stats
    val clockEstimate: StateFlow<ClockEstimate> get() = clockSync.estimate

    /** Live broadcast state, for the console and the quest step that drives the role. */
    val isBroadcasting get() = broadcaster.isBroadcasting

    /** What the platform accepted when tracking started, or null when this session does not track. */
    val poseTrackReport: PoseTrackReport? get() = poseReport

    /** What the broadcast actually put on air, or null when it never went on air. */
    val broadcast: BroadcastReport? get() = broadcastReport

    /** Platform posture of the tracker, for the console. Answerable with no session running. */
    fun poseDiagnostics(): List<String> = poseTracker.diagnostics()

    /**
     * Platform posture of the advertiser, for the console. Answerable with no session running.
     *
     * Reached through the instrument rather than by injecting [IdentityBroadcaster] into the console,
     * so the console keeps talking to one object about the session. Two references to the broadcaster
     * would let a screen start a broadcast outside a session, which would put an identity frame on air
     * with no sidecar behind it — a sighting the fleet records and nothing can explain.
     */
    fun broadcastDiagnostics(): List<String> = broadcaster.diagnostics()

    private var sessionEvents = mutableListOf<SessionEvent>()
    private val pendingTraffic = mutableListOf<TrafficSample>()
    private val pendingPose = mutableListOf<PoseSample>()

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
        broadcastReport = null
        broadcastActive = false
        broadcastStopJob = null
        poseReport = null
        poseCount = 0
        lastPose = null
        pendingPose.clear()
        meshRevisions = 0
        _poseProgress.value = PoseTrackProgress.IDLE
        _mesh.value = MeshProgress.IDLE
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

        // 1b — hold the screen, when the session's roles need the foreground.
        //
        // On iOS both advertising and odometry stop when the phone locks, and neither reports it: the
        // session stays open and the streams simply end. A walk is somebody holding a phone without
        // touching it, so auto-lock fires about thirty seconds in — which would make every walk
        // thirty seconds long, silently. Released in stop(), and not requested for a session that
        // plays neither foreground role.
        if (request.broadcast || request.track) {
            val held = wake.hold("MonadCount walk")
            event("screen_wake", held)
            note(held)
        }

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

        // 4b — the clock, for a session that has no collector to exchange with.
        //
        // Without this a walk records a trajectory, a mesh and an identity frame on a device-local
        // monotonic clock whose origin means nothing to anybody else — and the fleet's CSI, stamped by
        // chrony on the Unix epoch, could not be lined up with any of it. `clock.tsv` is the mapping and
        // this is the only path to it when there is no AP to associate to.
        //
        // Coarser than the UDP exchange, and the sidecar says which produced it. See [ReferenceClock].
        if (!request.emit) {
            _state.value = _state.value.copy(phase = Phase.SYNCING)
            runReferenceBurst(sessionId, request, opening = true)
            resyncJob = newScope.launch {
                val period = request.clockSync.resyncSeconds.coerceAtLeast(30) * 1000L
                // Same reasoning as the collector path: gate G4 fits an affine map and needs **two**
                // samples before the skew term is identifiable at all, so the second burst is pulled
                // forward rather than waiting a full resync period. A ten-minute walk would otherwise
                // ship one sample and be silently downgraded to offset-only.
                delay(period.coerceAtMost(SECOND_BURST_MILLIS))
                if (isActive) runReferenceBurst(sessionId, request, opening = false)
                while (isActive) {
                    delay(period)
                    runReferenceBurst(sessionId, request, opening = false)
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

        // 5b — broadcasting, when the session plays the role for its whole length. A refusal is
        // recorded and the session continues: a walk with no identity frame is still a walk.
        if (request.broadcast) {
            startBroadcast().onFailure {
                note("broadcast refused: ${it.message}")
                event("broadcast_failed", it.message ?: "")
            }
        }

        // 5c — the pose track. A refusal is recorded and the session continues, on the same rule as
        // a refused broadcast: a walk with no trajectory is still a walk, and it still carries its
        // scanned waypoints. What must never happen is a session that reports a track it does not
        // have, so the report is set only on success and the sidecar reads it rather than the request.
        if (request.track) {
            // Subscribe **before** starting, and that order is not tidiness. The tracker's flow has
            // no replay, so a pose emitted while nothing is collecting is dropped — and the sampler
            // begins polling inside start(). Collecting afterwards would silently lose the opening
            // moments of every walk, which are the ones a waypoint scanned at the start point needs.
            poseJob = poseTracker.samples
                .onEach { sample ->
                    lastPose = sample
                    poseCount++
                    _poseProgress.value = _poseProgress.value.plus(sample)
                    // Buffered under the same lock-free discipline as traffic: the sampler must keep
                    // its phase, so nothing on its path waits for SQLite.
                    pendingPose += sample
                    if (pendingPose.size >= POSE_BATCH) {
                        val batch = pendingPose.toList()
                        pendingPose.clear()
                        newScope.launch { repository.appendPose(sessionId, batch) }
                    }
                }
                .launchIn(newScope)

            poseTracker.start(request.trackRateHz)
                .onSuccess { report ->
                    poseReport = report
                    event("pose_tracking_started", "${report.implementation} @ ${report.commandedRateHz} Hz")
                    note(
                        "tracking at ${report.commandedRateHz} Hz " +
                            "(${if (report.depthAssisted) "depth-assisted" else "camera and IMU only"})"
                    )
                    report.notes.forEach { note(it) }
                    // A partial batch would otherwise sit in memory until the next batch filled, so
                    // a session killed mid-walk would lose up to POSE_BATCH poses — and a killed
                    // session is exactly the one whose last seconds matter.
                    poseFlushJob = newScope.launch {
                        while (isActive) {
                            delay(POSE_FLUSH_MILLIS)
                            if (pendingPose.isNotEmpty()) {
                                val batch = pendingPose.toList()
                                pendingPose.clear()
                                repository.appendPose(sessionId, batch)
                            }
                        }
                    }

                    // The geometry's clock. ARKit hands out no timestamps, so a block's history exists
                    // only if this loop writes it down — and without that history the exported mesh is a
                    // map with no time, which cannot be laid on a CSI window.
                    //
                    // Slow on purpose. Scene reconstruction settles over seconds, and the loop returns
                    // only *changed* blocks, so a faster cadence would spend the main thread walking the
                    // anchor set to discover nothing. It shares no work with the pose sampler.
                    meshJob = newScope.launch {
                        while (isActive) {
                            delay(MESH_OBSERVE_MILLIS)
                            val changed = runCatching { poseTracker.observeMesh() }.getOrNull()
                                ?: continue
                            if (changed.isEmpty()) continue
                            repository.appendMesh(sessionId, changed)
                            meshRevisions += changed.size
                            _mesh.value = _mesh.value.plus(changed)
                        }
                    }
                }
                .onFailure {
                    // Nothing will arrive, so the collector goes too rather than sitting on the
                    // session's scope for its whole length.
                    poseJob?.cancel()
                    poseJob = null
                    note("pose tracking refused: ${it.message}")
                    event("pose_tracking_failed", it.message ?: "")
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
            // Applicable only when the tracker actually started. A refused tracker must read as a
            // session that does not play the role, not as one whose track is dead.
            tracking = poseReport != null,
            poseRateHz = poseReport?.commandedRateHz ?: 0.0,
            // Disciplined either way now. Before the reference path existed this was gated on `emit`,
            // so a walk's clock stream read NOT_APPLICABLE — which is the one state that cannot fail,
            // and a walk whose sync had silently stopped looked exactly like a walk that never needed it.
            clockDisciplined = true,
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

    // ---- identity broadcast -----------------------------------------------------------------

    /**
     * Put the session's identity frame on air (the broadcaster role, [LabRole.BROADCASTER]).
     *
     * Callable mid-session — a `ble_advertise` quest step scopes the broadcast to itself — or from
     * [start] when the request plays the role for the whole session. The advertised UUID is derived
     * here, from the bundle's namespace plus the *running* session's identity, so a quest config
     * can never carry an identity and two sessions can never share a frame.
     *
     * The marker and the sidecar record the platform's **accepted** values, not the commanded ones.
     */
    suspend fun startBroadcast(
        durationSeconds: Int? = null,
        intervalMs: Int? = null,
        txPower: String? = null,
    ): Result<BroadcastReport> {
        val sessionId = _state.value.sessionId?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(IllegalStateException("no running session to identify"))
        val request = _state.value.request
            ?: return Result.failure(IllegalStateException("no session request"))
        if (broadcastActive) {
            return broadcastReport?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("broadcast already starting"))
        }
        val plan = request.advertise
        if (!plan.isConfigured) {
            return Result.failure(IllegalStateException("bundle has no advertise namespace"))
        }
        val uuid = AdvertiseIdentity.serviceUuid(plan.namespaceUuid, request.participantId, sessionId)
            ?: return Result.failure(IllegalArgumentException("malformed advertise namespace: ${plan.namespaceUuid}"))

        val started = broadcaster.start(
            BroadcastRequest(
                serviceUuid = uuid,
                intervalMs = intervalMs ?: plan.intervalMs,
                txPower = txPower ?: plan.txPower,
            )
        )
        return started
            .onSuccess { report ->
                broadcastActive = true
                broadcastReport = report
                event("broadcast_started", report.serviceUuid)
                mark(
                    kind = SessionMarker.Kind.BROADCAST_START,
                    label = "identity broadcast on air",
                    payload = json.encodeToString(BroadcastReport.serializer(), report),
                )
                if (durationSeconds != null && durationSeconds > 0) {
                    broadcastStopJob = scope?.launch {
                        delay(durationSeconds * 1_000L)
                        stopBroadcast("duration elapsed (${durationSeconds}s)")
                    }
                }
            }
    }

    /** Take the identity frame off the air. Safe to call when nothing is broadcasting. */
    suspend fun stopBroadcast(reason: String = "operator") {
        if (!broadcastActive) return
        broadcastActive = false
        broadcastStopJob?.cancel()
        broadcastStopJob = null
        broadcaster.stop()
        event("broadcast_stopped", reason)
        mark(kind = SessionMarker.Kind.BROADCAST_STOP, label = reason)
    }

    /**
     * Record a surveyed waypoint: a scanned marker card, and where the tracker thought the phone was.
     *
     * The pose is read from [lastPose] synchronously, so the correspondence is between the code and
     * the position at the moment of the tap rather than at the moment a write completed. A null pose
     * is recorded as null and not skipped: a waypoint with no position still fixes a time to a place,
     * and a reader has to be able to tell "no tracker" from "no waypoint".
     *
     * Refuses when no session is open, unlike [mark] which shrugs. A waypoint the operator believes
     * they recorded and did not is a hole in the geometry that only shows up in analysis, so this one
     * has to say no out loud.
     */
    suspend fun markWaypoint(code: String, annotation: String? = null): Result<WaypointMarkerPayload> {
        val sessionId = _state.value.sessionId?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(IllegalStateException("no running session to place a waypoint in"))
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("empty waypoint code"))

        val pose = lastPose
        val payload = WaypointMarkerPayload(
            code = trimmed,
            note = annotation?.trim()?.ifBlank { null },
            pose = pose?.let { WaypointPose.of(it) },
        )
        mark(
            kind = SessionMarker.Kind.WAYPOINT,
            label = trimmed,
            stepId = trimmed,
            payload = json.encodeToString(WaypointMarkerPayload.serializer(), payload),
        )
        note(
            if (pose == null) {
                "waypoint $trimmed — no pose (nothing is tracking)"
            } else {
                "waypoint $trimmed at " +
                    "(${format(pose.x.toDouble())}, ${format(pose.z.toDouble())}) m, " +
                    "tracking ${pose.quality.wire}"
            }
        )
        return Result.success(payload)
    }

    /**
     * Export the room and store it as a session artefact.
     *
     * Persisted rather than returned to a caller who might upload it: the house rule is
     * upload-then-delete, and a mesh held only in this process is lost the first time an upload fails —
     * which on an experiment network is the normal case rather than the exception.
     *
     * A failure is recorded and swallowed. A walk whose geometry could not be exported is still a walk
     * with a trajectory, an identity frame and its waypoints, and aborting the close path over the
     * largest and most optional artefact would strand the ones that always work.
     */
    private suspend fun exportMesh(sessionId: String): MeshSnapshot? {
        val snapshot = poseTracker.snapshotMesh().getOrElse {
            note("mesh export refused: ${it.message}")
            event("mesh_export_failed", it.message ?: "")
            return null
        }
        if (snapshot.isEmpty) {
            // Belt and braces: the platform already refuses an empty anchor set, and a header-only PLY
            // in the corpus is worse than a stated absence.
            note("mesh export produced no triangles — not stored")
            event("mesh_export_empty")
            return null
        }
        return runCatching {
            repository.putBlob(
                sessionId = sessionId,
                name = LabArtefact.MESH,
                contentType = MESH_CONTENT_TYPE,
                monotonicNanos = monotonicNanos(),
                wallMillis = currentTimeMillis(),
                bytes = snapshot.bytes,
            )
            event("mesh_exported", "${snapshot.faces} faces, ${snapshot.bytes.size} bytes")
            snapshot
        }.getOrElse {
            note("mesh could not be stored: ${it.message}")
            event("mesh_store_failed", it.message ?: "")
            null
        }
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

        // A broadcast still on air gets its closing edge before the marker stream closes, for the
        // same reason as the block above: an open interval at the end can only be resolved by
        // guessing, and the guess would land on the fleet-side join.
        stopBroadcast("session end")

        // One last heartbeat *before* the generators are told to stop, so the health record
        // describes the session as it ran rather than the instant after everything was shut down.
        val finalHealth = healthMonitor?.tick(
            counters = StreamCounters(
                illuminator = trafficGenerator.stats.value.sent,
                witness = _state.value.beaconCount,
                transitions = transitionCount,
                groundTruth = groundTruthCount,
                clock = clockSync.history.value.size.toLong(),
                pose = poseCount,
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
        // Mesh export **before** the tracker is stopped: pausing the ARSession discards the anchor set,
        // so reading it afterwards would return an empty room every time. This is the single ordering
        // constraint in the whole shutdown, and getting it wrong produces a header-only PLY.
        val mesh = if (request?.track == true && poseReport != null) {
            exportMesh(sessionId)
        } else {
            null
        }
        // Last look at the anchor set, on the same clock, so the log's final row is not older than the
        // geometry the file actually carries. Reads the frame the export just held, which the export also
        // paused the session on — so this describes exactly what was written, not a later state.
        runCatching { poseTracker.observeMesh() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            repository.appendMesh(sessionId, it)
            meshRevisions += it.size
        }
        // After that append, not before: the span is what the sidecar publishes as the observation
        // window, and reading it first would leave the final row outside the window it belongs to.
        val meshSpan = repository.meshSpan(sessionId)

        poseTracker.stop()
        poseJob?.cancel()
        poseFlushJob?.cancel()
        meshJob?.cancel()
        poseJob = null
        poseFlushJob = null
        meshJob = null
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

        // The tail of the track, after the scope that would have flushed it is gone. Written before
        // counts() is read, so the sidecar's sample count includes the last seconds of the walk.
        if (pendingPose.isNotEmpty()) {
            repository.appendPose(sessionId, pendingPose.toList())
            pendingPose.clear()
        }

        socket.close()
        // Before residency, and unconditionally: a phone left permanently awake because a session
        // aborted is a flat battery halfway through a lab afternoon, and release() on an unheld screen
        // is a no-op.
        wake.release()
        residency.release()
        event("session_closed")

        val endedMono = monotonicNanos()
        val endedWall = currentTimeMillis()
        val stats = trafficGenerator.stats.value
        val clock = clockSync.estimate.value
        val counts = repository.counts(sessionId)
        // Only when the session asked to track. Null and a zero-sample summary are different facts:
        // null says the walk deliberately recorded no trajectory, zero says it asked and got none.
        val poseSummary = if (request?.track == true) repository.poseSummary(sessionId) else null

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
                    // Recorded when the role actually went on air, not when it was merely asked
                    // for — a refused broadcast must not read as a broadcasting session.
                    if (broadcastReport != null) add(LabRole.BROADCASTER.name.lowercase())
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
                advertiseUuid = broadcastReport?.serviceUuid ?: "",
                advertiseInterval = broadcastReport?.acceptedInterval ?: "",
                advertiseTxPower = broadcastReport?.txPower ?: "",
                advertiseForegroundOnly = broadcastReport?.foregroundOnly,
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
                poseTrack = poseSummary,
                poseTracker = poseReport,
                waypoints = counts.waypoints,
                mesh = mesh?.summary(
                    firstObservedMonotonicNanos = meshSpan.firstMonotonicNanos,
                    lastObservedMonotonicNanos = meshSpan.lastMonotonicNanos,
                    revisions = meshRevisions,
                ),
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
        // Kept until the next session starts, not cleared here: the operator's last look at the
        // console is immediately after stopping, and that is exactly when "did the walk track?" is
        // the question. start() resets it.
        sessionIdBytes = ByteArray(0)
        zoneTracker = null
        note("session $sessionId closed — ${stats.sent} packets, ${counts.beacons} beacon rows, "
            + "${counts.markers} markers (${counts.blocks} block edges), ${counts.clock} clock "
            + "samples, ${counts.health} health checkpoints")
        poseSummary?.let { track ->
            // Path length is the line an operator actually reads. A forty-metre corridor that came
            // back as four metres is a tracker that never initialised, and this is the only place on
            // the phone that says so before the data leaves.
            note(
                "track: ${track.samples} poses, ${format(track.pathLengthMetres)} m walked, " +
                    "${(track.normalFraction * 100).toLong()} % tracking normal, " +
                    "${counts.waypoints} waypoint(s)"
            )
            if (track.normalFraction < POSE_TRUST_FLOOR) {
                note("POSE TRACK MOSTLY UNTRUSTED — its geometry should not be used as ground truth")
            }
        }
        mesh?.let {
            note(
                "mesh: ${it.anchors} block(s), ${it.vertices} vertices, ${it.faces} faces, " +
                    "${it.bytes.size / 1024} KiB" +
                    if (it.classified) ", classified" else ", unlabelled"
            )
        }
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
     * One reference-clock burst: exchange, persist, and leave a marker — the collector path's twin.
     *
     * Deliberately writes into the same `clock.tsv` and raises the same `CLOCK_SYNC` marker kind. Gate
     * G4's residual is measured **at sync markers**, so a burst that only wrote an offset would give the
     * analysis a number with no anchor on the shared stream to test it at. One rule, two paths.
     */
    private suspend fun runReferenceBurst(
        sessionId: String,
        request: SessionRequest,
        opening: Boolean,
    ) {
        clockSync.runReferenceBurst(referenceClock, request.clockSync)
            .onSuccess { estimate ->
                repository.appendClock(sessionId, estimate)
                runCatching {
                    repository.appendMarker(
                        sessionId,
                        SessionMarker(
                            kind = SessionMarker.Kind.CLOCK_SYNC,
                            label = "clock burst (${referenceClock.source})",
                            payload = "{\"offset_ns\":${estimate.offsetNanos}," +
                                "\"rtt_ns\":${estimate.delayNanos}," +
                                "\"skew_ppm\":${estimate.skewPpm}," +
                                "\"exchanges\":${estimate.samples}," +
                                "\"source\":\"${referenceClock.source}\"}",
                            monotonicNanos = estimate.anchorNanos,
                            wallMillis = currentTimeMillis(),
                        ),
                    )
                }.onFailure { Napier.w("[lab] clock marker dropped: ${it.message}") }
                if (opening) {
                    event(
                        "clock_sync",
                        "${referenceClock.source} offset=${estimate.offsetMillis}ms " +
                            "rtt=${estimate.delayMillis}ms",
                    )
                    note(
                        "clock offset ${format(estimate.offsetMillis)} ms " +
                            "(rtt ${format(estimate.delayMillis)} ms, ${referenceClock.source})"
                    )
                }
            }
            .onFailure {
                event("clock_sync_failed", it.message ?: "")
                if (opening) {
                    note(
                        "clock sync FAILED (${referenceClock.source}) — this walk cannot be placed on " +
                            "the fleet timeline"
                    )
                }
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
        broadcaster.stop()
        broadcastActive = false
        broadcastStopJob?.cancel()
        broadcastStopJob = null
        // The tracker and the screen hold are acquired inside start(), so an abort after either one
        // has to give them back. Leaving the screen held would drain the battery of a phone whose
        // session never began, which is the failure hardest to attribute to anything.
        poseTracker.stop()
        poseJob?.cancel()
        poseJob = null
        poseFlushJob?.cancel()
        poseFlushJob = null
        wake.release()
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

        /**
         * Poses per write.
         *
         * Far smaller than the traffic batch, because the rates differ by two orders of magnitude:
         * at 10 Hz this is a write every five seconds, whereas 250 would be a write every twenty-five
         * and would risk a longer stretch of unpersisted track. Paired with [POSE_FLUSH_MILLIS] so a
         * partial batch is never held longer than that either.
         */
        const val POSE_BATCH = 50

        /** Ceiling on how long a partial pose batch may sit unwritten. */
        const val POSE_FLUSH_MILLIS = 5_000L

        /**
         * How often the mesh anchor set is walked for changes.
         *
         * Three seconds. Scene reconstruction settles over seconds rather than frames, and the scan
         * returns only changed blocks — so a faster cadence would spend main-thread time discovering
         * nothing, on the same thread ARKit is using. Slow enough to be free, fast enough that a block
         * refined as the operator walks past it is attributed to the right few seconds of the walk.
         */
        const val MESH_OBSERVE_MILLIS = 3_000L

        /**
         * `application/octet-stream` for the PLY.
         *
         * `model/ply` is not a registered media type and `text/plain` would be a lie about a binary
         * body. The artefact's name carries the format and the sidecar states it, so nothing downstream
         * depends on this header.
         */
        const val MESH_CONTENT_TYPE = "application/octet-stream"

        /**
         * Below this trusted fraction the track is called out at close rather than left to analysis.
         *
         * 0.8 is not a statistical threshold, it is an operator threshold: a walk that spent a fifth
         * of itself relocalising is one the operator can simply re-take while still standing in the
         * room, and the whole value of saying so at close is that it is still cheap to fix.
         */
        const val POSE_TRUST_FLOOR = 0.8

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
