package sk.martinvanco.monad.lab.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.lab.data.GroundTruthRepository
import sk.martinvanco.monad.lab.data.LabSessionRecovery
import sk.martinvanco.monad.lab.data.LabSessionRepository
import sk.martinvanco.monad.lab.data.LabSessionUploader
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.LabSessionSidecar
import sk.martinvanco.monad.lab.domain.SessionReport
import sk.martinvanco.monad.lab.domain.ZoneMembership

/**
 * Drives the participant status screen.
 *
 * The instrument's health flow is the source of the live numbers; nothing here polls the radio or
 * touches a stream. Recovery of interrupted sessions runs on open too, because this is the screen a
 * participant reaches after the app has been killed and reopened, which is exactly when a stranded
 * session needs finding.
 */
class SessionStatusScreenModel(
    private val instrument: LabInstrument,
    private val groundTruth: GroundTruthRepository,
    private val uploader: LabSessionUploader,
    private val sessions: LabSessionRepository,
    private val recovery: LabSessionRecovery,
    private val users: UserRepository,
) : StateScreenModel<SessionStatusState>(SessionStatusState()) {

    private val json = Json { ignoreUnknownKeys = true }

    init {
        instrument.state
            .onEach { mutableState.value = mutableState.value.copy(instrument = it) }
            .launchIn(screenModelScope)

        instrument.health
            .onEach { mutableState.value = mutableState.value.copy(health = it) }
            .launchIn(screenModelScope)

        screenModelScope.launch {
            val recovered = runCatching { recovery.recover() }.getOrElse {
                Napier.w("[lab] session recovery failed: ${it.message}")
                emptyList()
            }
            mutableState.value = mutableState.value.copy(recovered = recovered)
            refresh()
        }
    }

    fun onEvent(event: SessionStatusEvent) {
        when (event) {
            SessionStatusEvent.Refresh -> screenModelScope.launch { refresh() }

            SessionStatusEvent.Dismiss ->
                mutableState.value = mutableState.value.copy(flushMessage = null)

            is SessionStatusEvent.PermissionChanged ->
                mutableState.value = mutableState.value.copy(permissions = event.statuses)

            SessionStatusEvent.Flush -> screenModelScope.launch {
                mutableState.value = mutableState.value.copy(isBusy = true, flushMessage = null)
                val report = runCatching { uploader.flush(purgeAfter = true) }.getOrNull()
                mutableState.value = mutableState.value.copy(
                    isBusy = false,
                    // Never a bare count. "0 sent" cannot distinguish "nothing to send" from
                    // "the server refused everything", and those call for opposite actions.
                    flushMessage = report?.headline
                        ?: "Could not reach the server. Everything is still on this phone.",
                )
                refresh()
            }
        }
    }

    private suspend fun refresh() {
        val user = users.getCurrentUser()
        val token = user?.backendId ?: user?.id?.toString().orEmpty()
        val labSession = groundTruth.lastScannedSession(token)
        val zone = labSession
            ?.let { ZoneMembership.resolve(groundTruth.eventsForParticipant(it, token)) }
            ?: mutableState.value.zone

        mutableState.value = mutableState.value.copy(
            zone = zone,
            pending = runCatching { uploader.pendingInventory() }
                .getOrElse { mutableState.value.pending },
            lastSession = lastClosedReport(),
        )
    }

    /**
     * The most recent session that has a sidecar, rendered as a report.
     *
     * Read from the stored sidecar rather than from live state so that it survives the app being
     * killed, and so that it is the *same* artefact the collection side will receive — a summary
     * derived from anything else could disagree with the uploaded data.
     */
    private suspend fun lastClosedReport(): SessionReport? =
        sessions.all()
            .asSequence()
            .mapNotNull { it.sidecarJson }
            .firstOrNull()
            ?.let { encoded ->
                runCatching { json.decodeFromString(LabSessionSidecar.serializer(), encoded) }
                    .map { SessionReport.from(it) }
                    .getOrNull()
            }
}
