package sk.martinvanco.monad.lab.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.lab.data.GroundTruthRepository
import sk.martinvanco.monad.lab.data.LabSessionUploader
import sk.martinvanco.monad.lab.domain.GroundTruthQr
import sk.martinvanco.monad.lab.domain.GroundTruthRecorder
import sk.martinvanco.monad.lab.domain.QrScan
import sk.martinvanco.monad.lab.domain.ZoneMembership
import sk.martinvanco.monad.lab.domain.ZoneState

/**
 * Drives the participant check-in / check-out scan.
 *
 * The flush is best-effort and never blocks the confirmation: the scan is already durable the
 * moment it is written to SQLite, and a participant standing in a doorway with no route to the
 * internet — the normal case once a phone has joined an experiment AP — must not be left waiting
 * on a network round-trip to find out whether they were counted.
 *
 * Every failure mode of the scan path has its own sentence. "Not our code", "code from a newer
 * build", "damaged code" and "you already scanned this" are four different things to do next, and
 * collapsing them into one red box trains a participant to ignore the box.
 */
class GroundTruthScanScreenModel(
    private val recorder: GroundTruthRecorder,
    private val groundTruth: GroundTruthRepository,
    private val uploader: LabSessionUploader,
    private val users: UserRepository,
) : StateScreenModel<GroundTruthScanState>(GroundTruthScanState()) {

    init {
        refresh()
    }

    fun onEvent(event: GroundTruthScanEvent) {
        when (event) {
            GroundTruthScanEvent.StartScan ->
                mutableState.value = mutableState.value.copy(
                    isScanning = true,
                    error = null,
                    notice = null,
                    flushMessage = null,
                    receipt = null,
                )

            GroundTruthScanEvent.StopScan ->
                mutableState.value = mutableState.value.copy(isScanning = false)

            GroundTruthScanEvent.Dismiss ->
                mutableState.value = mutableState.value.copy(
                    error = null,
                    notice = null,
                    receipt = null,
                    flushMessage = null,
                )

            is GroundTruthScanEvent.Scanned -> onScanned(event.raw)

            GroundTruthScanEvent.Flush -> screenModelScope.launch {
                mutableState.value = mutableState.value.copy(isBusy = true, flushMessage = null)
                // The report, not a count: "0 sent" means nothing on its own, and the participant
                // needs to know whether that was "nothing to send" or "the network refused".
                val report = runCatching { uploader.flush(purgeAfter = true) }.getOrNull()
                mutableState.value = mutableState.value.copy(
                    isBusy = false,
                    flushMessage = report?.headline ?: "Could not reach the server. Nothing was lost.",
                )
                refresh()
            }
        }
    }

    private fun onScanned(raw: String) {
        // The scanner keeps firing while the code is in frame. Once a scan has been accepted the
        // camera is closed, so the second callback lands here and is dropped rather than becoming a
        // second person.
        if (!mutableState.value.isScanning) return
        mutableState.value = mutableState.value.copy(isScanning = false)

        val ticket = when (val parsed = GroundTruthQr.parse(raw)) {
            is QrScan.Ok -> parsed.ticket
            else -> {
                mutableState.value = mutableState.value.copy(error = parsed.message)
                return
            }
        }

        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(isBusy = true)
            // The dataset carries the pseudonym, never the account e-mail — the same token the
            // session sidecar and the collector announcement already use, so ground truth joins to
            // the rest of a participant's data without a lookup table.
            val token = participantToken()
            recorder.record(ticket, token)
                .onSuccess { receipt ->
                    val notice = when {
                        receipt.isDuplicate -> {
                            val seconds = ((receipt.duplicateOfAgeMillis ?: 0L) / 1000).coerceAtLeast(1)
                            "Already recorded ${seconds}s ago — you are counted once, not twice."
                        }

                        receipt.sessionChangedFrom != null ->
                            "This code is for a different lab session than your last scan. You are " +
                                "now checked into the session on this code."

                        receipt.movedFrom != null ->
                            "Moved from ${receipt.movedFrom} — you were checked out of it automatically."

                        else -> null
                    }
                    mutableState.value = mutableState.value.copy(
                        receipt = receipt,
                        error = null,
                        notice = notice,
                        zone = zoneFor(ticket.labSessionId, token),
                    )
                    // Opportunistic: succeeds on Wi-Fi with a route, fails silently in a pocket.
                    runCatching { uploader.flushGroundTruth() }
                }
                .onFailure { failure ->
                    mutableState.value = mutableState.value.copy(
                        error = failure.message ?: "The scan was not recorded. Try again.",
                    )
                }
            mutableState.value = mutableState.value.copy(isBusy = false)
            refresh()
        }
    }

    private suspend fun participantToken(): String {
        val user = users.getCurrentUser()
        return user?.backendId ?: user?.id?.toString().orEmpty()
    }

    private suspend fun zoneFor(labSessionId: String, token: String): ZoneState =
        ZoneMembership.resolve(groundTruth.eventsForParticipant(labSessionId, token))

    private fun refresh() {
        screenModelScope.launch {
            val token = participantToken()
            val session = groundTruth.lastScannedSession(token)
            mutableState.value = mutableState.value.copy(
                pendingCount = groundTruth.pendingCount(),
                zone = session?.let { zoneFor(it, token) } ?: mutableState.value.zone,
            )
        }
    }
}
