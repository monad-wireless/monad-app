package sk.martinvanco.monad.lab.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.lab.data.GroundTruthRepository
import sk.martinvanco.monad.lab.data.LabSessionUploader
import sk.martinvanco.monad.lab.domain.CheckInOutcome
import sk.martinvanco.monad.lab.domain.CheckInService
import sk.martinvanco.monad.lab.domain.CheckInState
import sk.martinvanco.monad.lab.domain.CheckOutReason

/**
 * Drives the check-in screen.
 *
 * It holds almost nothing. The check-in itself lives in [CheckInService], on a process-wide scope,
 * because it outlives this screen by hours — the participant closes the app, the phone locks, and
 * the visit keeps being counted. A screen model that owned the state would end the check-in every
 * time somebody pressed back, which is the bug this arrangement exists to make impossible.
 *
 * What is left here is the camera's state and one sentence of feedback.
 */
class CheckInScreenModel(
    private val checkIn: CheckInService,
    private val groundTruth: GroundTruthRepository,
    private val uploader: LabSessionUploader,
    private val users: UserRepository,
) : StateScreenModel<CheckInScreenState>(CheckInScreenState()) {

    /** The live check-in. Read straight from the service so there is one copy of the truth. */
    val checkInState: StateFlow<CheckInState> = checkIn.state

    /** Ticks once a second while a check-in runs, so the elapsed time on screen moves. */
    val now: StateFlow<Long> = checkIn.now

    init {
        refreshPending()
    }

    fun onEvent(event: CheckInScreenEvent) {
        when (event) {
            CheckInScreenEvent.StartScan ->
                mutableState.value = CheckInScreenState(isScanning = true)

            CheckInScreenEvent.StopScan ->
                mutableState.value = mutableState.value.copy(isScanning = false)

            CheckInScreenEvent.Dismiss ->
                mutableState.value = mutableState.value.copy(message = null, isError = false)

            is CheckInScreenEvent.Scanned -> onScanned(event.raw)

            CheckInScreenEvent.CheckOut -> screenModelScope.launch {
                mutableState.value = mutableState.value.copy(isBusy = true)
                report(checkIn.checkOut(CheckOutReason.TAPPED))
            }

            CheckInScreenEvent.Flush -> screenModelScope.launch {
                mutableState.value = mutableState.value.copy(isBusy = true, message = null)
                // The report rather than a count: "0 sent" alone cannot distinguish "nothing to
                // send" from "the network refused", and those need different things from a person.
                val report = runCatching { uploader.flush(purgeAfter = true) }.getOrNull()
                mutableState.value = mutableState.value.copy(
                    isBusy = false,
                    message = report?.headline ?: "Could not reach the server. Nothing was lost.",
                    isError = false,
                )
                refreshPending()
            }
        }
    }

    private fun onScanned(raw: String) {
        // The scanner keeps firing while a code is in frame. Once one scan is accepted the camera
        // closes, so the second callback lands here and is dropped rather than checking somebody
        // straight back out again.
        if (!mutableState.value.isScanning) return
        mutableState.value = mutableState.value.copy(isScanning = false, isBusy = true)

        screenModelScope.launch {
            report(checkIn.onScan(raw, participantToken()))
            // Opportunistic: succeeds on a network with a route out, fails silently in a pocket.
            // The row is already durable in SQLite, so nothing is riding on this.
            runCatching { uploader.flushGroundTruth() }
            refreshPending()
        }
    }

    /** One sentence per outcome. Four different things to do next are four different sentences. */
    private fun report(outcome: CheckInOutcome) {
        val (message, isError) = when (outcome) {
            is CheckInOutcome.CheckedIn -> {
                val suffix = if (outcome.place.isUnknownCard) {
                    " No quest names this card, so it is recorded by its code."
                } else {
                    ""
                }
                Pair("Checked in at ${outcome.place.display}.$suffix", false)
            }

            is CheckInOutcome.CheckedOut -> {
                val suffix = if (outcome.reason == CheckOutReason.TIMED_OUT) {
                    " The app closed it automatically after three hours."
                } else {
                    ""
                }
                Pair(
                    "Checked out of ${outcome.place.display} after " +
                        "${formatDuration(outcome.durationMillis)}.$suffix",
                    false,
                )
            }

            is CheckInOutcome.Moved -> Pair(
                "Moved to ${outcome.to.display}. You were checked out of ${outcome.from.display}.",
                false,
            )

            CheckInOutcome.NotOurCard -> Pair(
                "That is not one of our cards. Look for a MonadCount code — they start with MONAD.",
                true,
            )

            is CheckInOutcome.Failed -> Pair(outcome.reason, true)
        }
        mutableState.value = mutableState.value.copy(
            isBusy = false,
            message = message,
            isError = isError,
        )
    }

    private suspend fun participantToken(): String {
        val user = users.getCurrentUser()
        return user?.backendId ?: user?.id?.toString().orEmpty()
    }

    private fun refreshPending() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(pendingCount = groundTruth.pendingCount())
        }
    }
}

/** `1 h 04 m`, `12 m 30 s`, `44 s`. Display only. */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "$hours h ${minutes.toString().padStart(2, '0')} m"
        minutes > 0 -> "$minutes m ${seconds.toString().padStart(2, '0')} s"
        else -> "$seconds s"
    }
}

data class CheckInScreenState(
    val isScanning: Boolean = false,
    val isBusy: Boolean = false,
    /** One sentence about what the last action did. Null when there is nothing to say. */
    val message: String? = null,
    val isError: Boolean = false,
    /** Scans still on this phone. Shown only when there are any. */
    val pendingCount: Long = 0,
)

sealed interface CheckInScreenEvent {
    data object StartScan : CheckInScreenEvent
    data object StopScan : CheckInScreenEvent
    data object Dismiss : CheckInScreenEvent
    data object CheckOut : CheckInScreenEvent
    data object Flush : CheckInScreenEvent
    data class Scanned(val raw: String) : CheckInScreenEvent
}
