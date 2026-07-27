package sk.martinvanco.monad.lab.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.lab.data.LabConfigService
import sk.martinvanco.monad.lab.data.LabSessionRepository
import sk.martinvanco.monad.lab.data.LabSessionUploader
import sk.martinvanco.monad.lab.domain.BackgroundResidency
import sk.martinvanco.monad.lab.domain.BeaconWitness
import sk.martinvanco.monad.lab.domain.CollectorEndpoint
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.SessionRequest
import sk.martinvanco.monad.lab.domain.SessionStatus

/**
 * Drives the lab console.
 *
 * Sessions started here are *bench* sessions: no quest, no enrolment, and an explicit AP/profile
 * choice by the operator. That is the mode the EXP-P3 gates are measured in — one phone, one rig,
 * a rate ladder — and it deliberately shares the same [LabInstrument] and the same session records
 * as a field run, so what is measured on the bench is what runs in the field.
 */
class LabConsoleScreenModel(
    private val instrument: LabInstrument,
    private val configService: LabConfigService,
    private val sessions: LabSessionRepository,
    private val uploader: LabSessionUploader,
    private val residency: BackgroundResidency,
    private val witness: BeaconWitness,
    private val users: UserRepository,
) : StateScreenModel<LabConsoleState>(LabConsoleState()) {

    init {
        observe()
        screenModelScope.launch {
            configService.loadCached()
            configService.refresh()
            refreshSessions()
            refreshDiagnostics()
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
                )
            }
            .launchIn(screenModelScope)

        configService.source
            .onEach { mutableState.value = mutableState.value.copy(configSource = it) }
            .launchIn(screenModelScope)

        instrument.state
            .onEach { mutableState.value = mutableState.value.copy(instrument = it) }
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
    }

    fun onEvent(event: LabConsoleEvent) {
        when (event) {
            LabConsoleEvent.RefreshConfig -> screenModelScope.launch {
                busy { configService.refresh() }
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
        }
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
}
