package sk.martinvanco.monad.lab.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.lab.data.LabConfigService
import sk.martinvanco.monad.lab.data.LabSessionRepository
import sk.martinvanco.monad.lab.data.LabSessionUploader
import sk.martinvanco.monad.lab.data.PreflightService
import sk.martinvanco.monad.lab.domain.BackgroundResidency
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.SessionMarker
import sk.martinvanco.monad.lab.domain.SessionRequest
import sk.martinvanco.monad.lab.domain.SessionStatus
import sk.martinvanco.monad.lab.domain.TrackingQuality
import sk.martinvanco.monad.lab.domain.WaypointMarkerPayload
import sk.martinvanco.monad.lab.domain.monotonicNanos
import sk.martinvanco.monad.lab.domain.preflight.SessionIntent

/**
 * Drives the lab console.
 *
 * Sessions started here are **walks**: the phone advertises a session identity the fleet's passive BLE
 * scan can hear, records its own trajectory while it does, and the operator marks surveyed waypoints
 * that tie the two coordinate frames together. See [LabConsoleState] for why the illuminator half of
 * the old console is gone.
 *
 * One deliberate difference from what this model used to do: it sets `broadcast = true` on the session
 * request. The previous version never did — `SessionRequest.broadcast` defaults to false and nothing
 * here overrode it — so a console-started session put nothing on air, and a phone could only advertise
 * inside a quest with a `ble_advertise` step. That was the single reason a bench walk produced no
 * identity frame, and it looked like a working session while it did.
 */
class LabConsoleScreenModel(
    private val instrument: LabInstrument,
    private val configService: LabConfigService,
    private val sessions: LabSessionRepository,
    private val uploader: LabSessionUploader,
    private val residency: BackgroundResidency,
    private val users: UserRepository,
    private val preflight: PreflightService,
) : StateScreenModel<LabConsoleState>(LabConsoleState()) {

    private val json = Json { ignoreUnknownKeys = true }

    init {
        observe()
        screenModelScope.launch {
            configService.loadCached()
            configService.refresh(users.getCurrentUser()?.token)
            refreshSessions()
            refreshDiagnostics()
            refreshWaypoints()
        }
        tickDisplayClock()
    }

    /**
     * A one-second display tick — and the pace of every high-rate readout.
     *
     * Separate from the instrument's health heartbeat on purpose: this is a *view* clock, it stops
     * when the console closes, and the instrument must not gain work on behalf of a screen.
     *
     * The pose and mesh readouts are **read here, not collected**. They used to be collected per
     * emission, which recomposed the whole console at the pose rate — ten full recompositions a
     * second of a long scrolling column, on a debug build, on the device that is also running
     * ARKit and the camera. Measured 2026-08-19: main-thread CPU doubled against the same walk
     * with the readouts sampled. A human reads these numbers at 1 Hz; the screen now changes at
     * the rate it is read.
     */
    private fun tickDisplayClock() {
        screenModelScope.launch {
            while (isActive) {
                mutableState.value = mutableState.value.copy(
                    nowMonotonicNanos = monotonicNanos(),
                    poseProgress = instrument.poseProgress.value,
                    mesh = instrument.meshProgress.value,
                    // Sampled here with the other high-rate readouts, for the reason in this
                    // function's own doc: the decode runs at 2 Hz and collecting it per emission
                    // would recompose the whole console twice a second. An offer a human reads is
                    // fine at 1 Hz.
                    detectedCard = instrument.seenCard.value,
                )
                delay(DISPLAY_TICK_MILLIS)
            }
        }
    }

    private fun observe() {
        configService.config
            .onEach { config ->
                mutableState.value = mutableState.value.copy(
                    config = config,
                    manualAdvertiseNamespace = mutableState.value.manualAdvertiseNamespace
                        .ifBlank { config.advertise.namespaceUuid },
                    // Witnessing follows the bundle rather than a remembered preference: a toggle left
                    // on from a deployment that had anchors would arm a stream that cannot produce a
                    // row, and the health monitor would then watch it fail.
                    witnessEnabled = mutableState.value.witnessEnabled && config.beacons.isConfigured,
                )
            }
            .launchIn(screenModelScope)

        configService.source
            .onEach { mutableState.value = mutableState.value.copy(configSource = it) }
            .launchIn(screenModelScope)

        instrument.state
            .onEach {
                mutableState.value = mutableState.value.copy(
                    instrument = it,
                    poseReport = instrument.poseTrackReport,
                    broadcastReport = instrument.broadcast,
                )
                refreshWaypoints()
            }
            .launchIn(screenModelScope)

        instrument.isBroadcasting
            .onEach { mutableState.value = mutableState.value.copy(isBroadcasting = it) }
            .launchIn(screenModelScope)

        // poseProgress and meshProgress are deliberately NOT collected — the display tick samples
        // them at 1 Hz. Collecting them here recomposed the console at the pose rate, which is
        // what made the screen freezy on a real walk. They live on the instrument, so nothing is
        // lost: backing out mid-walk and returning shows the accumulated track either way.

        instrument.clockEstimate
            .onEach { mutableState.value = mutableState.value.copy(clock = it) }
            .launchIn(screenModelScope)

        instrument.log
            .onEach { mutableState.value = mutableState.value.copy(log = it) }
            .launchIn(screenModelScope)

        instrument.health
            .onEach { mutableState.value = mutableState.value.copy(health = it) }
            .launchIn(screenModelScope)

        uploader.lastReport
            .onEach { mutableState.value = mutableState.value.copy(lastFlush = it) }
            .launchIn(screenModelScope)
    }

    fun onEvent(event: LabConsoleEvent) {
        when (event) {
            LabConsoleEvent.RefreshConfig -> screenModelScope.launch {
                busy { configService.refresh(users.getCurrentUser()?.token) }
                refreshDiagnostics()
            }

            LabConsoleEvent.StartSession -> startSession()

            // The stop path has a gate, not a lock: a walk about to close with unfixable holes gets
            // one sentence naming them, while the operator is still standing where fixing is cheap.
            // Both 2026-08-19 walks closed with zero waypoints and nothing said so until analysis.
            LabConsoleEvent.StopSession -> {
                val warning = mutableState.value.stopWarningText
                if (warning == null) {
                    stopSession()
                } else {
                    mutableState.value = mutableState.value.copy(stopWarning = warning)
                }
            }

            LabConsoleEvent.ConfirmStopSession -> {
                mutableState.value = mutableState.value.copy(stopWarning = null)
                stopSession()
            }

            LabConsoleEvent.DismissStopWarning ->
                mutableState.value = mutableState.value.copy(stopWarning = null)

            is LabConsoleEvent.ToggleCameraPreview ->
                mutableState.value = mutableState.value.copy(showCameraPreview = event.shown)

            is LabConsoleEvent.ToggleSiteMap ->
                mutableState.value = mutableState.value.copy(useSiteMap = event.enabled)

            LabConsoleEvent.StartDwell -> screenModelScope.launch { startDwell() }
            LabConsoleEvent.EndDwell -> screenModelScope.launch { endDwell() }

            LabConsoleEvent.RequestPrerequisites -> screenModelScope.launch {
                residency.requestPrerequisites()
                refreshDiagnostics()
            }

            LabConsoleEvent.RetryUploads -> screenModelScope.launch {
                busy {
                    val uploaded = uploader.uploadPending(purgeAfter = true)
                    message("uploaded $uploaded session(s)")
                    refreshSessions()
                }
            }

            LabConsoleEvent.ClearLog -> instrument.clearLog()

            is LabConsoleEvent.DeleteSession -> screenModelScope.launch {
                sessions.forceDelete(event.sessionId)
                refreshSessions()
                message("session deleted locally")
            }

            is LabConsoleEvent.ToggleBroadcast ->
                mutableState.value = mutableState.value.copy(broadcastEnabled = event.enabled)

            is LabConsoleEvent.ToggleTrack ->
                mutableState.value = mutableState.value.copy(trackEnabled = event.enabled)

            is LabConsoleEvent.ToggleWitness ->
                mutableState.value = mutableState.value.copy(
                    witnessEnabled = event.enabled && mutableState.value.witnessAvailable
                )

            is LabConsoleEvent.SelectTrackRate ->
                mutableState.value = mutableState.value.copy(trackRateHz = event.rateHz)

            LabConsoleEvent.StartBroadcast -> screenModelScope.launch {
                instrument.startBroadcast()
                    .onSuccess { message("on air as ${it.serviceUuid}") }
                    .onFailure { message("broadcast refused: ${it.message}") }
                mutableState.value = mutableState.value.copy(broadcastReport = instrument.broadcast)
            }

            LabConsoleEvent.StopBroadcast -> screenModelScope.launch {
                instrument.stopBroadcast("operator")
                message("off air")
            }

            is LabConsoleEvent.SelectWaypointPoint ->
                mutableState.value = mutableState.value.copy(
                    waypointPoint = event.point
                        .coerceIn(1, LabConsoleState.FINGERPRINT_CARD_COUNT),
                    // Selecting from the numbered pool clears a typed code, so the button never shows
                    // one card and records another.
                    waypointCode = "",
                )

            is LabConsoleEvent.UpdateWaypointCode ->
                mutableState.value = mutableState.value.copy(waypointCode = event.value)

            is LabConsoleEvent.MarkWaypoint -> screenModelScope.launch {
                markWaypoint(event.code ?: mutableState.value.pendingWaypointCode)
            }

            LabConsoleEvent.StartWaypointScan -> {
                // Refused rather than silently allowed while the tracker holds the camera. Opening a
                // second capture session interrupts one of the two, and the one that loses is decided
                // by the OS — so the trajectory could develop a hole at the exact instant the waypoint
                // is meant to anchor it.
                if (mutableState.value.poseReport != null) {
                    message("the camera is tracking — type or tap the card code instead")
                } else {
                    mutableState.value = mutableState.value.copy(isScanning = true)
                }
            }

            LabConsoleEvent.StopWaypointScan ->
                mutableState.value = mutableState.value.copy(isScanning = false)

            is LabConsoleEvent.WaypointScanned -> screenModelScope.launch {
                mutableState.value = mutableState.value.copy(isScanning = false)
                markWaypoint(LabConsoleState.waypointCodeFrom(event.raw))
            }

            LabConsoleEvent.RunPreflight -> screenModelScope.launch { runPreflight() }

            is LabConsoleEvent.UpdateAdvertiseNamespace ->
                mutableState.value = mutableState.value.copy(manualAdvertiseNamespace = event.value)

            LabConsoleEvent.ApplyAdvertiseNamespace -> screenModelScope.launch {
                applyAdvertiseNamespace()
            }
        }
    }

    /** The tracker's live camera for the preview, or null when nothing is tracking. */
    fun previewHandle(): Any? = instrument.posePreviewHandle()

    // ---- dwell -------------------------------------------------------------------------

    /**
     * Open a dwell on the pending card: record the waypoint (the position fix) and a `dwell_start`
     * marker (the condition edge). Two markers because they are two facts — the fix survives even
     * if the operator forgets to close the dwell.
     */
    private suspend fun startDwell() {
        val state = mutableState.value
        if (state.isDwelling) {
            message("already dwelling on ${state.dwellCode} — end it first")
            return
        }
        val code = state.pendingWaypointCode
        instrument.markWaypoint(code)
            .onFailure {
                message("dwell refused: ${it.message}")
                return
            }
        instrument.mark(
            kind = SessionMarker.Kind.DWELL_START,
            label = "dwell $code",
            stepId = code,
            payload = "{\"code\":\"$code\"}",
        )
        mutableState.value = mutableState.value.copy(
            dwellCode = code,
            dwellStartedMonotonicNanos = monotonicNanos(),
        )
        message("dwelling on $code — stand still until you end it")
        refreshWaypoints()
    }

    private suspend fun endDwell() {
        val state = mutableState.value
        val code = state.dwellCode ?: return
        val durationMillis = (monotonicNanos() - state.dwellStartedMonotonicNanos) / 1_000_000
        instrument.mark(
            kind = SessionMarker.Kind.DWELL_END,
            label = "dwell $code done",
            stepId = code,
            payload = "{\"code\":\"$code\",\"duration_ms\":$durationMillis}",
        )
        mutableState.value = mutableState.value.copy(dwellCode = null, dwellStartedMonotonicNanos = 0)
        message("dwell on $code closed after ${durationMillis / 1000} s")
    }

    // ---- waypoints -------------------------------------------------------------------------

    private suspend fun markWaypoint(code: String) {
        instrument.markWaypoint(code)
            .onSuccess { payload ->
                message(
                    if (payload.pose == null) {
                        "waypoint ${payload.code} — no pose (nothing is tracking)"
                    } else {
                        "waypoint ${payload.code} recorded"
                    }
                )
                refreshWaypoints()
            }
            .onFailure { message("waypoint refused: ${it.message}") }
    }

    /**
     * Read the session's waypoints back out of the marker stream.
     *
     * From storage rather than from an in-memory list, deliberately. The markers are what the analysis
     * will read, so a console list built any other way could disagree with the artefact — and the one
     * question this list answers is "did the waypoint I just tapped actually get written?".
     */
    private suspend fun refreshWaypoints() {
        val sessionId = mutableState.value.instrument.sessionId?.takeIf { it.isNotEmpty() }
        if (sessionId == null) {
            mutableState.value = mutableState.value.copy(waypoints = emptyList())
            return
        }
        val rows = runCatching { sessions.markers(sessionId) }
            .getOrDefault(emptyList())
            .filter { it.kind == SessionMarker.Kind.WAYPOINT }
            .mapNotNull { marker -> marker.toWaypointRow() }
            .sortedByDescending { it.wallMillis }
        mutableState.value = mutableState.value.copy(waypoints = rows)
    }

    /**
     * A waypoint marker as a console row.
     *
     * Falls back to the marker's own label when the payload will not parse, rather than dropping the
     * row: a waypoint written by an older build, or with a payload this build cannot read, is still a
     * waypoint that happened, and hiding it would make the console disagree with the artefact in the
     * one direction that matters.
     */
    private fun SessionMarker.toWaypointRow(): WaypointRow? {
        val decoded = payload?.let {
            runCatching { json.decodeFromString(WaypointMarkerPayload.serializer(), it) }
                .onFailure { error -> Napier.d("[lab] unreadable waypoint payload: ${error.message}") }
                .getOrNull()
        }
        val code = decoded?.code ?: label.takeIf { it.isNotBlank() } ?: return null
        return WaypointRow(
            code = code,
            wallMillis = wallMillis,
            x = decoded?.pose?.x,
            z = decoded?.pose?.z,
            quality = TrackingQuality.fromWire(decoded?.pose?.quality),
        )
    }

    // ---- pre-flight ------------------------------------------------------------------------

    /**
     * Run the readiness check for the walk that is about to run.
     *
     * Judged as a walk, not as an illuminator session, and that is the whole reason it is worth
     * pressing. Before the intent existed this always reported at least three hard blockers — no
     * collector, no access point, no clock samples — none of which a walk needs, and a readiness
     * display that is permanently red is one the operator stops reading.
     */
    private suspend fun runPreflight() {
        val state = mutableState.value
        mutableState.value = state.copy(preflightRunning = true)
        val report = runCatching {
            preflight.run(
                config = state.config,
                commandedRateHz = 0.0,
                sessionRunning = state.isRunning,
                intent = SessionIntent.WALK,
                trackRequested = state.trackEnabled,
            )
        }.getOrNull()
        mutableState.value = mutableState.value.copy(
            preflightRunning = false,
            preflight = report ?: mutableState.value.preflight,
        )
        message(report?.headline ?: "pre-flight could not run")
        refreshDiagnostics()
    }

    // ---- session ---------------------------------------------------------------------------

    private fun startSession() {
        val state = mutableState.value
        val config = state.config
        screenModelScope.launch {
            busy {
                val user = users.getCurrentUser()
                val request = SessionRequest(
                    participantId = user?.backendId ?: user?.id?.toString() ?: "bench",
                    collector = config.collector,
                    beacons = config.beacons,
                    clockSync = config.clockSync,
                    site = config.site,
                    configVersion = config.version,
                    // No illumination from this console. There is no access point on this deployment
                    // to associate to, so an emit=true request would fail at the socket and abort the
                    // walk — which is a worse outcome than not offering the role at all.
                    emit = false,
                    witness = state.witnessEnabled && config.beacons.isConfigured,
                    broadcast = state.broadcastEnabled,
                    advertise = config.advertise,
                    track = state.trackEnabled,
                    trackRateHz = state.trackRateHz,
                    loadSiteMap = state.useSiteMap,
                )
                instrument.start(request)
                    .onSuccess { message("walk $it started") }
                    .onFailure { message("start refused: ${it.message}") }
                refreshDiagnostics()
                refreshSessions()
                refreshWaypoints()
            }
        }
    }

    private fun stopSession() {
        screenModelScope.launch {
            busy {
                // A dwell still open at stop gets its closing edge first, so the interval never has
                // to be resolved by guessing — the same rule the instrument applies to blocks.
                if (mutableState.value.isDwelling) endDwell()
                instrument.stop()
                    .onSuccess { message("walk $it closed — uploading") }
                    .onFailure { message("stop failed: ${it.message}") }
                uploader.uploadPending(purgeAfter = true)
                refreshSessions()
            }
        }
    }

    /**
     * Override the advertise namespace locally.
     *
     * Validated against the identity codec rather than by shape, because a namespace that parses but
     * carries non-zero identity bytes would collide with another handset's frame — and the fleet would
     * attribute two phones' sightings to one.
     */
    private suspend fun applyAdvertiseNamespace() {
        val typed = mutableState.value.manualAdvertiseNamespace.trim()
        if (typed.isBlank()) {
            message("namespace is empty")
            return
        }
        val current = mutableState.value.config
        val probe = sk.martinvanco.monad.lab.domain.AdvertiseIdentity
            .serviceUuid(typed, "probe", "probe")
        if (probe == null) {
            message("that is not a 128-bit UUID")
            return
        }
        configService.overrideLocally(
            current.copy(advertise = current.advertise.copy(namespaceUuid = typed))
        )
        message("advertise namespace applied locally")
    }

    private suspend fun refreshSessions() {
        val rows = sessions.all().map {
            SessionRow(
                sessionId = it.sessionId,
                status = SessionStatus.fromStorage(it.status).name.lowercase(),
                startedWallMillis = it.startedWallMs,
                participantId = it.participantId,
                socketPinned = it.socketPinned == 1L,
                boundInterface = it.boundInterface ?: "",
                uploadError = it.uploadError,
            )
        }
        mutableState.value = mutableState.value.copy(
            sessions = rows,
            unsyncedCount = sessions.unsyncedCount(),
        )
    }

    private fun refreshDiagnostics() {
        mutableState.value = mutableState.value.copy(
            residency = residency.diagnostics(),
            trackerDiagnostics = runCatching { instrument.poseDiagnostics() }
                .getOrDefault(emptyList()),
            broadcastDiagnostics = runCatching { instrument.broadcastDiagnostics() }
                .getOrDefault(emptyList()),
        )
    }

    private fun message(text: String) {
        mutableState.value = mutableState.value.copy(message = text)
    }

    private suspend fun <T> busy(block: suspend () -> T): T {
        mutableState.value = mutableState.value.copy(isBusy = true)
        return try {
            block()
        } finally {
            mutableState.value = mutableState.value.copy(isBusy = false)
        }
    }

    private companion object {
        /** Display tick for elapsed time. One second is the resolution a person reads a timer at. */
        const val DISPLAY_TICK_MILLIS = 1_000L
    }
}

/** Milliseconds as `mm:ss`, or `h:mm:ss` past the hour. Shared by the console's two timers. */
internal fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}
