package sk.martinvanco.monad.lab.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.lab.data.GroundTruthRepository
import sk.martinvanco.monad.lab.data.LabConfigService
import sk.martinvanco.monad.lab.data.LabSessionRepository
import sk.martinvanco.monad.lab.data.LabSessionUploader
import sk.martinvanco.monad.lab.data.PreflightService
import sk.martinvanco.monad.lab.data.RoomTallyGateway
import sk.martinvanco.monad.lab.domain.BackgroundResidency
import sk.martinvanco.monad.lab.domain.BeaconWitness
import sk.martinvanco.monad.lab.domain.BlockCommandResult
import sk.martinvanco.monad.lab.domain.BlockGuards
import sk.martinvanco.monad.lab.domain.BlockStartRequest
import sk.martinvanco.monad.lab.domain.CollectorEndpoint
import sk.martinvanco.monad.lab.domain.GroundTruthQr
import sk.martinvanco.monad.lab.domain.GroundTruthTicket
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.SessionRequest
import sk.martinvanco.monad.lab.domain.LabZones
import sk.martinvanco.monad.lab.domain.SessionStatus
import sk.martinvanco.monad.lab.domain.monotonicNanos
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Drives the lab console.
 *
 * Sessions started here are *bench* sessions: no quest, no enrolment, and an explicit AP/profile
 * choice by the operator. That is the mode the EXP-P3 gates are measured in — one phone, one rig,
 * a rate ladder — and it deliberately shares the same [LabInstrument] and the same session records
 * as a field run, so what is measured on the bench is what runs in the field.
 */
@OptIn(ExperimentalUuidApi::class)
class LabConsoleScreenModel(
    private val instrument: LabInstrument,
    private val configService: LabConfigService,
    private val sessions: LabSessionRepository,
    private val uploader: LabSessionUploader,
    private val residency: BackgroundResidency,
    private val witness: BeaconWitness,
    private val users: UserRepository,
    private val groundTruth: GroundTruthRepository,
    private val roomTally: RoomTallyGateway,
    private val preflight: PreflightService,
) : StateScreenModel<LabConsoleState>(LabConsoleState()) {

    init {
        observe()
        screenModelScope.launch {
            configService.loadCached()
            configService.refresh(users.getCurrentUser()?.token)
            refreshSessions()
            refreshDiagnostics()
            refreshGroundTruth()
        }
        pollRoomTally()
        tickBlockClock()
    }

    /**
     * A one-second display tick for the running block.
     *
     * Separate from the instrument's health heartbeat on purpose: this is a *view* clock, it must
     * stop when the console closes, and the instrument must not gain work on behalf of a screen.
     * It reads `monotonicNanos()` and recomputes the pure over-budget guard — nothing else.
     */
    private fun tickBlockClock() {
        screenModelScope.launch {
            while (isActive) {
                val now = monotonicNanos()
                val active = mutableState.value.blocks.active
                mutableState.value = mutableState.value.copy(
                    blockNowMonotonicNanos = now,
                    blockLiveWarnings = active?.let { BlockGuards.whileRunning(it, now) }.orEmpty(),
                )
                delay(BLOCK_TICK_MILLIS)
            }
        }
    }

    /**
     * Poll the server-side room tally for as long as the console is open.
     *
     * A loop rather than a refresh-on-event, because the number it fetches changes without this
     * device doing anything — that is the entire point of it. The cadence is fast enough that the
     * operator sees a participant appear a second or two after they scan, and trivial for the
     * backend: the aggregate is two indexed reads over a few hundred rows.
     *
     * The clock is advanced on **every** tick, including failures, so the displayed age keeps
     * growing while the backend is unreachable instead of freezing at the last success and looking
     * fresh.
     */
    private fun pollRoomTally() {
        screenModelScope.launch {
            while (isActive) {
                fetchRoomTally()
                delay(ROOM_TALLY_POLL_MILLIS)
            }
        }
    }

    private suspend fun fetchRoomTally() {
        val sessionId = mutableState.value.instrument.sessionId
        val now = currentTimeMillis()

        if (sessionId.isNullOrBlank()) {
            // No session, no room: the printed code is anchored to the instrument's session, so
            // there is nothing for participants to have scanned into yet.
            mutableState.value = mutableState.value.copy(
                roomTally = null,
                roomTallyAtMillis = null,
                roomTallyNowMillis = now,
                roomTallyError = null,
            )
            return
        }

        val fetched = roomTally.tally(sessionId, users.getCurrentUser()?.token)
        mutableState.value = if (fetched != null) {
            mutableState.value.copy(
                roomTally = fetched,
                roomTallyAtMillis = now,
                roomTallyNowMillis = now,
                roomTallyError = null,
            )
        } else {
            // Keep the last good snapshot and let it age. Blanking it would discard the only
            // room-wide number the operator has over a single dropped request.
            mutableState.value.copy(
                roomTallyNowMillis = now,
                roomTallyError = "backend unreachable",
            )
        }
    }

    private fun observe() {
        configService.config
            .onEach { config ->
                mutableState.value = mutableState.value.copy(
                    config = config,
                    selectedApId = mutableState.value.selectedApId ?: config.accessPoints.firstOrNull()?.id,
                    selectedProfileId = mutableState.value.selectedProfileId
                        ?: config.trafficProfiles.firstOrNull()?.id,
                    manualHost = mutableState.value.manualHost.ifBlank { config.collector.host },
                    manualBeaconUuid = mutableState.value.manualBeaconUuid.ifBlank { config.beacons.uuid },
                    groundTruthZoneId = mutableState.value.groundTruthZoneId
                        .ifBlank { config.beacons.zones.firstOrNull()?.cellId.orEmpty() },
                    // Block and ground-truth zones share one vocabulary on purpose: `zone_id` in
                    // `markers.tsv` and `zone_id` in `ground_truth.tsv` are joined directly by the
                    // analysis, and two vocabularies would need a lookup table nobody would write.
                    blockZoneId = mutableState.value.blockZoneId.ifBlank {
                        config.beacons.zones.firstOrNull()?.cellId
                            ?: LabZones.DEFAULT.first()
                    },
                )
                regenerateGroundTruthQr()
            }
            .launchIn(screenModelScope)

        configService.source
            .onEach { mutableState.value = mutableState.value.copy(configSource = it) }
            .launchIn(screenModelScope)

        instrument.state
            .onEach {
                mutableState.value = mutableState.value.copy(instrument = it)
                // The code is anchored to the running session, so it appears when a session starts
                // and disappears when it stops. There is no window in which the console offers a
                // code that scans into nothing.
                regenerateGroundTruthQr()
                refreshGroundTruth()
            }
            .launchIn(screenModelScope)

        instrument.trafficStats
            .onEach { mutableState.value = mutableState.value.copy(traffic = it) }
            .launchIn(screenModelScope)

        instrument.clockEstimate
            .onEach { mutableState.value = mutableState.value.copy(clock = it) }
            .launchIn(screenModelScope)

        instrument.log
            .onEach { mutableState.value = mutableState.value.copy(log = it) }
            .launchIn(screenModelScope)

        // Health carries the clock gate, and the clock gate is what says whether a block boundary
        // lands inside G4b's 250 ms. The operator has to see that while it can still be fixed.
        instrument.health
            .onEach { mutableState.value = mutableState.value.copy(health = it) }
            .launchIn(screenModelScope)

        instrument.blocks
            .onEach { mutableState.value = mutableState.value.copy(blocks = it) }
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
            LabConsoleEvent.StopSession -> stopSession()

            LabConsoleEvent.RunClockBurst -> screenModelScope.launch {
                message("clock burst runs as part of session start; start a session to measure")
            }

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

            LabConsoleEvent.ApplyManualCollector -> screenModelScope.launch {
                val port = mutableState.value.manualPort.toIntOrNull()
                if (mutableState.value.manualHost.isBlank() || port == null || port !in 1..65535) {
                    message("host/port invalid")
                    return@launch
                }
                val current = mutableState.value.config
                configService.overrideLocally(
                    current.copy(
                        collector = CollectorEndpoint(
                            host = mutableState.value.manualHost.trim(),
                            udpPort = port,
                        ),
                        beacons = current.beacons.copy(
                            uuid = mutableState.value.manualBeaconUuid.trim().ifBlank { current.beacons.uuid }
                        ),
                    )
                )
                message("local override applied")
            }

            is LabConsoleEvent.SelectAp ->
                mutableState.value = mutableState.value.copy(selectedApId = event.apId)

            is LabConsoleEvent.SelectProfile ->
                mutableState.value = mutableState.value.copy(selectedProfileId = event.profileId)

            is LabConsoleEvent.UpdateManualHost ->
                mutableState.value = mutableState.value.copy(manualHost = event.value)

            is LabConsoleEvent.UpdateManualPort ->
                mutableState.value = mutableState.value.copy(
                    manualPort = event.value.filter { it.isDigit() }.take(5)
                )

            is LabConsoleEvent.UpdateManualBeaconUuid ->
                mutableState.value = mutableState.value.copy(manualBeaconUuid = event.value)

            is LabConsoleEvent.DeleteSession -> screenModelScope.launch {
                sessions.forceDelete(event.sessionId)
                refreshSessions()
                message("session deleted locally")
            }

            is LabConsoleEvent.SelectGroundTruthZone -> screenModelScope.launch {
                mutableState.value = mutableState.value.copy(groundTruthZoneId = event.zoneId)
                regenerateGroundTruthQr()
                refreshGroundTruth()
            }

            LabConsoleEvent.RefreshGroundTruth -> screenModelScope.launch {
                refreshGroundTruth()
                fetchRoomTally()
            }

            LabConsoleEvent.RefreshRoomTally -> screenModelScope.launch { fetchRoomTally() }

            LabConsoleEvent.FlushGroundTruth -> screenModelScope.launch {
                busy {
                    val sent = uploader.flushGroundTruth()
                    message("ground truth flushed for $sent session/participant pair(s)")
                    refreshGroundTruth()
                    // The room number is what the operator will look at next, and the flush just
                    // changed it.
                    fetchRoomTally()
                }
            }

            is LabConsoleEvent.SelectBlockZone ->
                mutableState.value = mutableState.value.copy(blockZoneId = event.zoneId)

            is LabConsoleEvent.SelectBlockLevel ->
                mutableState.value = mutableState.value.copy(blockLevel = event.level)

            is LabConsoleEvent.SelectBlockSubCondition ->
                mutableState.value = mutableState.value.copy(blockSubCondition = event.subCondition)

            is LabConsoleEvent.SelectBlockKind ->
                mutableState.value = mutableState.value.copy(blockKind = event.kind)

            LabConsoleEvent.StartBlock -> screenModelScope.launch { startBlock() }
            LabConsoleEvent.StopBlock -> screenModelScope.launch { stopBlock() }

            LabConsoleEvent.RunPreflight -> screenModelScope.launch { runPreflight() }
        }
    }

    // ---- block control ---------------------------------------------------------------------

    private suspend fun startBlock() {
        val state = mutableState.value
        val zoneId = state.blockZoneId.ifBlank { state.blockZoneOptions.firstOrNull().orEmpty() }
        val result = instrument.startBlock(
            request = BlockStartRequest(
                // Globally unique and generated once, at the tap, so both edges of the block and
                // every analysis join agree on one identifier.
                blockId = Uuid.random().toString(),
                zoneId = zoneId,
                level = state.blockLevel,
                subCondition = state.blockSubCondition,
                kind = state.blockKind,
            ),
            tally = state.blockTally,
        )
        applyBlockResult(result)
    }

    private suspend fun stopBlock() {
        applyBlockResult(instrument.stopBlock(tally = mutableState.value.blockTally))
    }

    private fun applyBlockResult(result: BlockCommandResult) {
        when (result) {
            is BlockCommandResult.Rejected -> {
                mutableState.value = mutableState.value.copy(blockLastWarnings = emptyList())
                message(result.reason.message)
            }

            is BlockCommandResult.Applied -> {
                mutableState.value = mutableState.value.copy(blockLastWarnings = result.warnings)
                message(
                    result.marks.lastOrNull()?.label?.let { "block $it" }
                        ?: "block command applied"
                )
            }
        }
    }

    // ---- pre-flight ------------------------------------------------------------------------

    /**
     * Run the readiness check.
     *
     * Deliberately explicit rather than automatic on screen open: it takes the datagram socket for
     * a few seconds, and an operator who did not ask for that would find the console mysteriously
     * busy. It is a button, and the answer it gives — "this phone will fail the clock gate" — is
     * the one worth having *before* ten people are standing in a room.
     */
    private suspend fun runPreflight() {
        val state = mutableState.value
        mutableState.value = state.copy(preflightRunning = true)
        val report = runCatching {
            preflight.run(
                config = state.config,
                commandedRateHz = state.selectedProfileId
                    ?.let { state.config.trafficProfile(it)?.rateHz }
                    ?: 0.0,
                sessionRunning = state.isRunning,
            )
        }.getOrNull()
        mutableState.value = mutableState.value.copy(
            preflightRunning = false,
            preflight = report ?: mutableState.value.preflight,
        )
        message(report?.headline ?: "pre-flight could not run")
        refreshDiagnostics()
    }

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
                    accessPoint = state.selectedApId?.let { config.accessPoint(it) },
                    trafficProfile = state.selectedProfileId?.let { config.trafficProfile(it) },
                    clockSync = config.clockSync,
                    site = config.site,
                    configVersion = config.version,
                    emit = state.selectedProfileId != null && config.isIlluminationReady,
                    witness = config.beacons.isConfigured,
                )
                instrument.start(request)
                    .onSuccess { message("session $it started") }
                    .onFailure { message("start refused: ${it.message}") }
                refreshDiagnostics()
                refreshSessions()
            }
        }
    }

    private fun stopSession() {
        screenModelScope.launch {
            busy {
                instrument.stop()
                    .onSuccess { message("session $it closed — uploading") }
                    .onFailure { message("stop failed: ${it.message}") }
                uploader.uploadPending(purgeAfter = true)
                refreshSessions()
            }
        }
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

    /**
     * Rebuild the check-in code from the running session, the selected zone, and the site.
     *
     * The session id is the *instrument's* — the operator's own recording is what defines the lab
     * session, and every participant's scan then keys to it. No session, no code: a code naming a
     * session that does not exist produces scans nothing can be joined to, which is strictly worse
     * than an operator noticing there is nothing to display.
     */
    private fun regenerateGroundTruthQr() {
        val state = mutableState.value
        val sessionId = state.instrument.sessionId?.takeIf { it.isNotEmpty() && state.isRunning }
        val zoneId = state.groundTruthZoneId
        mutableState.value = state.copy(
            groundTruthQrPayload = if (sessionId != null && zoneId.isNotBlank()) {
                GroundTruthQr.encode(
                    GroundTruthTicket(
                        labSessionId = sessionId,
                        zoneId = zoneId,
                        site = state.config.site,
                        // Toggle: one printed code per doorway, and each participant's own history
                        // decides which way it counts them.
                        declaredDirection = null,
                    )
                )
            } else {
                null
            }
        )
    }

    private suspend fun refreshGroundTruth() {
        val sessionId = mutableState.value.instrument.sessionId
        mutableState.value = mutableState.value.copy(
            groundTruthCheckedIn = sessionId?.let { groundTruth.checkedIn(it) } ?: emptyList(),
            groundTruthPending = groundTruth.pendingCount(),
        )
    }

    private fun refreshDiagnostics() {
        mutableState.value = mutableState.value.copy(
            residency = residency.diagnostics(),
            witnessDiagnostics = witness.residencyDiagnostics(),
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
        /** Display tick for the running block's elapsed time and its over-budget guard. */
        const val BLOCK_TICK_MILLIS = 1_000L

        /**
         * Room-tally poll cadence.
         *
         * Fast enough that a participant appears on the console a moment after they scan, and well
         * inside the 15 s staleness bound so a single dropped request does not flap the label.
         */
        const val ROOM_TALLY_POLL_MILLIS = 4_000L
    }
}
