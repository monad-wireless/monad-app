package sk.martinvanco.monad.lab.data

import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.lab.domain.LabArtefact
import sk.martinvanco.monad.lab.domain.upload.ArtefactOutcome
import sk.martinvanco.monad.lab.domain.upload.ArtefactSink
import sk.martinvanco.monad.lab.domain.upload.FlushReport
import sk.martinvanco.monad.lab.domain.upload.PendingInventory
import sk.martinvanco.monad.lab.domain.upload.RetryPolicy
import sk.martinvanco.monad.lab.domain.upload.TallyOutcome

/**
 * Uploads a closed session's artefacts, then — and only then — releases the local copy.
 *
 * The rule is **upload-then-delete**, and it is the whole reason this class exists. The app's
 * older quest path exported to TSV, posted it, and dropped the local rows on the completion path
 * regardless of what the server said; a failed upload therefore discarded the only copy of a
 * field session. Here a failure marks the session `failed`, leaves every byte on disk, and
 * surfaces it as unsynced until it succeeds.
 *
 * The sidecar is uploaded **last**. Its presence in the object store is then a reliable marker
 * that the session is complete, which lets the reader side treat a prefix without one as a
 * partial upload rather than as a session with missing streams.
 *
 * Three properties were added for the August session:
 *
 * - **Bounded retry with backoff** per artefact ([RetryPolicy]). A phone joined to an experiment AP
 *   normally has no route to the internet at all, so retrying forever would burn an hour of battery
 *   on a link that cannot succeed until the participant walks out of the room — while a single
 *   attempt would fail a whole session on one Wi-Fi handover.
 * - **A report instead of a count.** The old manual flush returned an integer, which cannot tell
 *   "nothing to send" from "everything failed": both are zero. [FlushReport] makes that distinction
 *   impossible to lose, names every artefact, and carries a `discarded` counter that is zero by
 *   construction and displayed anyway — a claim nobody displays is a claim nobody checks.
 * - **Pending inventory per artefact**, so "3 sessions pending" can be read as the rows it actually
 *   is.
 */
class LabSessionUploader(
    private val repository: LabSessionRepository,
    private val sink: ArtefactSink,
    private val users: UserRepository,
    private val groundTruth: GroundTruthRepository,
    private val tallyService: RoomTallyGateway,
    private val retry: RetryPolicy = RetryPolicy.LAB,
) {
    private val _progress = MutableStateFlow<UploadProgress?>(null)
    val progress: StateFlow<UploadProgress?> = _progress.asStateFlow()

    /** The last flush, so a screen opened after the fact still shows what happened. */
    private val _lastReport = MutableStateFlow<FlushReport?>(null)
    val lastReport: StateFlow<FlushReport?> = _lastReport.asStateFlow()

    /** Everything still on this device, per artefact. */
    suspend fun pendingInventory(): PendingInventory = repository.pendingInventory(
        groundTruthRows = groundTruth.pendingCount(),
        groundTruthNotInTally = groundTruth.pendingIngestCount(),
    )

    /**
     * Upload every closed or previously-failed session. Returns how many fully succeeded.
     *
     * Kept returning `Int` because it is the count-shaped answer the quest completion path and the
     * lab console both want. Callers that need to *show* what happened read [flush] or [lastReport].
     */
    suspend fun uploadPending(purgeAfter: Boolean = true): Int = flush(purgeAfter).sessionsUploaded

    /**
     * The same drain, reported in full.
     *
     * Ground truth drains on the same trigger as everything else, so every existing flush point —
     * session stop, quest finish, the console's retry button — already drains it. A scan taken
     * while the app was offline in a pocket leaves on the first opportunity, without the participant
     * being asked to do anything.
     */
    suspend fun flush(purgeAfter: Boolean = true): FlushReport {
        val startedAt = currentTimeMillis()
        val token = users.getCurrentUser()?.token
        if (token.isNullOrBlank()) {
            Napier.w("[lab] upload skipped: not authenticated")
            return FlushReport.skipped("you are not signed in", startedAt).also { _lastReport.value = it }
        }
        val pending = repository.pendingUpload()
        val outcomes = mutableListOf<ArtefactOutcome>()
        var uploaded = 0
        pending.forEachIndexed { index, record ->
            _progress.value = UploadProgress(record.sessionId, index + 1, pending.size, LabArtefact.SIDECAR)
            val sessionOutcomes = uploadOneReported(record.sessionId, token, purgeAfter)
            outcomes += sessionOutcomes
            if (sessionOutcomes.isNotEmpty() && sessionOutcomes.all { it.succeeded }) uploaded++
        }
        val groundTruthResult = flushGroundTruthReported(token)
        // The room aggregate goes last and on its own. It is an operational convenience — what
        // lets the console show a room count instead of one handset's count — and the TSV above is
        // the science, so nothing here may delay, gate, or fail the artefact upload.
        val tally = submitTally(token)
        _progress.value = null

        val report = FlushReport(
            startedWallMillis = startedAt,
            finishedWallMillis = currentTimeMillis(),
            sessionsAttempted = pending.size,
            sessionsUploaded = uploaded,
            groundTruthFilesSent = groundTruthResult.first,
            groundTruthFilesFailed = groundTruthResult.second,
            outcomes = outcomes,
            // Nothing in this class deletes an artefact it did not get an acknowledgement for.
            discarded = 0,
            tally = tally,
        )
        _lastReport.value = report
        return report
    }

    /**
     * Push unsent scans to the room-wide aggregate.
     *
     * Three properties, each load-bearing:
     *
     * - **Never fatal.** A phone joined to an experiment AP normally cannot reach the backend at
     *   all. An unreachable endpoint leaves every row pending and returns; the scans are already
     *   durable in SQLite and already on their way to S3.
     * - **A server answer is final for that batch.** The endpoint is never all-or-nothing: it
     *   returns per-event outcomes, and `duplicates` is a success — idempotency is a unique index
     *   on `scan_nonce`, so re-sending a whole session is the intended behaviour. Rows the server
     *   has *seen* are marked ingested even when it refused them, because a rejection will not
     *   improve on retry and an unbounded resend loop would be the silent failure this pass exists
     *   to remove.
     * - **Conflicts are surfaced, never swallowed.** A conflicting nonce is pre-registration
     *   exclusion E3; by analysis time the affected interval is already gone and nobody can ask
     *   what happened, so it has to be visible while somebody is still standing in the room.
     */
    private suspend fun submitTally(token: String): TallyOutcome {
        val pending = groundTruth.pendingIngest()
        if (pending.isEmpty()) return TallyOutcome.NOT_ATTEMPTED

        val receipt = tallyService.submit(pending, token)
            ?: return TallyOutcome(attempted = pending.size, unreachable = true)

        groundTruth.markIngested(pending.map { it.scanNonce })
        if (receipt.conflicts > 0 || receipt.rejected > 0) {
            Napier.w(
                "[lab] room tally: ${receipt.conflicts} conflict(s), ${receipt.rejected} " +
                    "rejected out of ${pending.size} — E3 territory, tell the operator"
            )
        }
        return TallyOutcome(
            attempted = pending.size,
            accepted = receipt.accepted,
            duplicates = receipt.duplicates,
            conflicts = receipt.conflicts,
            rejected = receipt.rejected,
        )
    }

    /**
     * Send buffered ground-truth scans. Returns how many (session, participant) files went up.
     *
     * Whole-file replace: every flush re-renders the participant's complete set for that session,
     * so a session that flushes three times ends with one object containing all of it rather than
     * with the last fragment. Rows are marked sent, never deleted — see [GroundTruthRepository].
     *
     * A failure here is logged and swallowed. Ground truth must never be able to fail a session
     * upload: the samples are the expensive artefact, and the scans are still on disk to retry.
     */
    suspend fun flushGroundTruth(token: String? = null): Int {
        val bearer = token ?: users.getCurrentUser()?.token
        if (bearer.isNullOrBlank()) {
            Napier.w("[lab] ground truth flush skipped: not authenticated")
            return 0
        }
        val sent = flushGroundTruthReported(bearer).first
        // The scan screen calls this opportunistically the instant a code is scanned, which is the
        // moment the operator most wants the room count to move. The aggregate goes after the
        // artefact, and cannot fail it.
        submitTally(bearer)
        return sent
    }

    /** @return (sent, failed) */
    private suspend fun flushGroundTruthReported(token: String): Pair<Int, Int> {
        val keys = groundTruth.pendingKeys()
        var sent = 0
        var failed = 0
        keys.forEach { key ->
            val content = groundTruth.groundTruthTsv(key)
            val outcome = attempt(
                sessionId = key.labSessionId,
                artefact = LabArtefact.GROUND_TRUTH,
                rows = groundTruth.pendingCountFor(key),
                content = content,
                contentType = TSV,
                token = token,
                participantId = key.participantToken,
            )
            if (outcome.succeeded) {
                groundTruth.markUploaded(key)
                sent++
            } else {
                failed++
                Napier.w(
                    "[lab] ground truth for ${key.labSessionId.take(8)} not sent, retained: " +
                        "${outcome.error}"
                )
            }
        }
        if (sent > 0) Napier.i("[lab] ground truth flushed for $sent session/participant pair(s)")
        return sent to failed
    }

    suspend fun uploadOne(sessionId: String, token: String, purgeAfter: Boolean = true): Boolean {
        val outcomes = uploadOneReported(sessionId, token, purgeAfter)
        return outcomes.isNotEmpty() && outcomes.all { it.succeeded }
    }

    private suspend fun uploadOneReported(
        sessionId: String,
        token: String,
        purgeAfter: Boolean,
    ): List<ArtefactOutcome> {
        val record = repository.byId(sessionId) ?: return emptyList()
        val sidecar = record.sidecarJson
        if (sidecar.isNullOrBlank()) {
            repository.markFailed(sessionId, "session has no sidecar — was it closed?")
            return listOf(
                ArtefactOutcome(
                    sessionId = sessionId,
                    artefact = LabArtefact.SIDECAR,
                    rows = 0,
                    bytes = 0,
                    attempts = 0,
                    succeeded = false,
                    error = "session has no sidecar — was it closed?",
                )
            )
        }

        val counts = repository.counts(sessionId)
        val outcomes = mutableListOf<ArtefactOutcome>()

        /**
         * One stream. Rendered lazily, one at a time: a thirty-minute session at 200 Hz is ~360 000
         * traffic rows, and materialising all five TSVs before sending any of them would put tens
         * of megabytes on the heap of a backgrounded phone for no reason.
         */
        suspend fun step(artefact: String, rows: Long, render: suspend () -> ByteArray): Boolean {
            val outcome = attempt(
                sessionId = sessionId,
                artefact = artefact,
                rows = rows,
                content = render(),
                contentType = TSV,
                token = token,
                participantId = record.participantId,
            )
            outcomes += outcome
            if (!outcome.succeeded) {
                // Stop at the first failure so the sidecar is never uploaded over an incomplete
                // stream set — that would mark the prefix complete when it is not.
                repository.markFailed(sessionId, "$artefact: ${outcome.error}")
                Napier.w("[lab] session $sessionId upload failed at $artefact, data retained")
            }
            return outcome.succeeded
        }

        // Streams first, sidecar last: the sidecar's presence marks completeness. The order is not
        // an optimisation, it is the contract the reader side uses to detect a partial upload.
        if (!step(LabArtefact.TRAFFIC, counts.traffic) { repository.trafficTsv(sessionId) }) return outcomes
        if (!step(LabArtefact.BEACONS, counts.beacons) { repository.beaconsTsv(sessionId) }) return outcomes
        if (!step(LabArtefact.TRANSITIONS, counts.transitions) { repository.transitionsTsv(sessionId) }) return outcomes
        if (!step(LabArtefact.CLOCK, counts.clock) { repository.clockTsv(sessionId) }) return outcomes
        if (!step(LabArtefact.MARKERS, counts.markers) { repository.markersTsv(sessionId) }) return outcomes
        if (!step(LabArtefact.HEALTH, counts.health) { repository.healthTsv(sessionId) }) return outcomes

        val sidecarOutcome = attempt(
            sessionId = sessionId,
            artefact = LabArtefact.SIDECAR,
            rows = 1,
            content = sidecar.encodeToByteArray(),
            contentType = JSON,
            token = token,
            participantId = record.participantId,
        )
        outcomes += sidecarOutcome
        if (!sidecarOutcome.succeeded) {
            repository.markFailed(sessionId, "${LabArtefact.SIDECAR}: ${sidecarOutcome.error}")
            return outcomes
        }

        repository.markUploaded(sessionId)
        if (purgeAfter) repository.purgeUploaded(sessionId)
        Napier.i("[lab] session $sessionId uploaded${if (purgeAfter) " and purged" else ""}")
        return outcomes
    }

    /**
     * One artefact, with bounded backoff.
     *
     * Every failure is retried the same way. Distinguishing "retryable" from "fatal" would mean
     * classifying platform exceptions by message across Ktor/OkHttp/Darwin, which is exactly the
     * kind of guess that fails silently in the field; a small fixed budget costs at most a few
     * seconds on a genuinely fatal error and is right on every transient one.
     */
    private suspend fun attempt(
        sessionId: String,
        artefact: String,
        rows: Long,
        content: ByteArray,
        contentType: String,
        token: String,
        participantId: String,
    ): ArtefactOutcome {
        _progress.value = _progress.value?.copy(artefact = artefact)
        var lastError: String? = null
        for (attemptNumber in 1..retry.maxAttempts) {
            val wait = retry.delayBeforeMillis(attemptNumber)
            if (wait > 0) delay(wait)
            val result = runCatching {
                sink.put(
                    sessionId = sessionId,
                    participantId = participantId,
                    artefact = artefact,
                    content = content,
                    contentType = contentType,
                    token = token,
                )
            }
            if (result.isSuccess) {
                return ArtefactOutcome(
                    sessionId = sessionId,
                    artefact = artefact,
                    rows = rows,
                    bytes = content.size,
                    attempts = attemptNumber,
                    succeeded = true,
                )
            }
            val error = result.exceptionOrNull()
            lastError = error?.message ?: error?.let { it::class.simpleName } ?: "unknown"
            Napier.w("[lab] $artefact attempt $attemptNumber/${retry.maxAttempts} failed: $lastError")
        }
        return ArtefactOutcome(
            sessionId = sessionId,
            artefact = artefact,
            rows = rows,
            bytes = content.size,
            attempts = retry.maxAttempts,
            succeeded = false,
            error = lastError,
        )
    }

    private companion object {
        const val TSV = "text/tab-separated-values"
        const val JSON = "application/json"
    }
}

data class UploadProgress(
    val sessionId: String,
    val index: Int,
    val total: Int,
    val artefact: String,
)
