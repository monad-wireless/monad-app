package sk.martinvanco.monad.lab.domain.upload

/**
 * Bounded exponential backoff for artefact uploads.
 *
 * Bounded in both directions, and for different reasons. The **attempt** cap exists because a phone
 * that has joined an experiment AP normally has no route to the internet at all: retrying forever
 * would burn battery for an hour on a link that cannot succeed until the participant leaves the
 * room, and the data is safe on disk either way. The **delay** cap exists because the opposite case
 * — a brief server hiccup — must not push the next attempt half an hour out.
 *
 * Deterministic, with no jitter. Jitter buys thundering-herd protection, and the herd here is ten
 * phones uploading a few hundred kilobytes each; predictability at the bench is worth more.
 */
data class RetryPolicy(
    val maxAttempts: Int = 4,
    val baseDelayMillis: Long = 1_000,
    val factor: Double = 3.0,
    val maxDelayMillis: Long = 30_000,
) {
    init {
        require(maxAttempts >= 1) { "at least one attempt" }
        require(baseDelayMillis >= 0) { "base delay must not be negative" }
        require(factor >= 1.0) { "backoff must not shrink" }
    }

    /** True while [attempt] (1-based, already made) leaves another one in the budget. */
    fun shouldRetry(attempt: Int): Boolean = attempt in 1 until maxAttempts

    /**
     * Delay to wait **before** [attempt] (1-based). The first attempt is immediate; the n-th waits
     * `base · factor^(n-2)`, capped.
     */
    fun delayBeforeMillis(attempt: Int): Long {
        if (attempt <= 1) return 0
        var delay = baseDelayMillis.toDouble()
        repeat(attempt - 2) { delay *= factor }
        return delay.coerceAtMost(maxDelayMillis.toDouble()).toLong()
    }

    /** Total time a full budget of retries can spend waiting. Useful for a "this may take" line. */
    val worstCaseWaitMillis: Long
        get() = (1..maxAttempts).sumOf { delayBeforeMillis(it) }

    companion object {
        /**
         * The lab default. Four attempts spend at most 1 s + 3 s + 9 s ≈ 13 s of waiting per
         * artefact, which is short enough that a participant pressing "Send now" gets an answer,
         * and long enough to ride out a Wi-Fi handover.
         */
        val LAB = RetryPolicy()
    }
}

/** What happened to one artefact of one session. */
data class ArtefactOutcome(
    val sessionId: String,
    val artefact: String,
    /** Rows in the rendered stream, so a "succeeded" line still says how much went. */
    val rows: Long,
    val bytes: Int,
    val attempts: Int,
    val succeeded: Boolean,
    val error: String? = null,
)

/**
 * The result of one flush, in enough detail to answer "what actually succeeded?".
 *
 * The manual flush button used to return an integer, which cannot distinguish "nothing to send"
 * from "everything failed". Both are zero. This type makes that distinction impossible to lose.
 *
 * [discarded] is a standing zero by construction — nothing in the upload path deletes an artefact
 * it did not get an acknowledgement for. It is reported anyway, because a number that is always
 * zero is a claim, and a claim that is never displayed is a claim nobody checks.
 */
data class FlushReport(
    val startedWallMillis: Long = 0,
    val finishedWallMillis: Long = 0,
    val sessionsAttempted: Int = 0,
    val sessionsUploaded: Int = 0,
    val groundTruthFilesSent: Int = 0,
    val groundTruthFilesFailed: Int = 0,
    val outcomes: List<ArtefactOutcome> = emptyList(),
    val discarded: Int = 0,
    /** What the room-wide ground-truth aggregate said. */
    val tally: TallyOutcome = TallyOutcome.NOT_ATTEMPTED,
    /** Non-null when the flush did not run at all — not signed in, nothing pending. */
    val skippedReason: String? = null,
) {
    val artefactsSucceeded: Int get() = outcomes.count { it.succeeded }
    val artefactsFailed: Int get() = outcomes.count { !it.succeeded }
    val rowsSent: Long get() = outcomes.filter { it.succeeded }.sumOf { it.rows }
    val bytesSent: Long get() = outcomes.filter { it.succeeded }.sumOf { it.bytes.toLong() }
    val sessionsFailed: Int get() = sessionsAttempted - sessionsUploaded
    val retriesUsed: Int get() = outcomes.sumOf { (it.attempts - 1).coerceAtLeast(0) }

    val isClean: Boolean
        get() = skippedReason == null && artefactsFailed == 0 && groundTruthFilesFailed == 0 &&
            discarded == 0 && tally.isClean

    val didNothing: Boolean
        get() = outcomes.isEmpty() && groundTruthFilesSent == 0 && groundTruthFilesFailed == 0 &&
            !tally.wasAttempted

    /** The one line a participant or operator reads after pressing the button. */
    val headline: String
        get() = buildString {
            append(
                when {
                    skippedReason != null -> "Nothing sent — $skippedReason"
                    didNothing -> "Nothing to send. Everything on this phone is already uploaded."
                    artefactsFailed == 0 && groundTruthFilesFailed == 0 ->
                        "Sent $artefactsSucceeded file(s), $rowsSent row(s), from " +
                            "$sessionsUploaded session(s). Nothing left pending."

                    else -> "Sent $artefactsSucceeded file(s); $artefactsFailed failed and are " +
                        "still on this phone. Nothing was discarded."
                }
            )
            tally.line?.let { append(" ").append(it) }
        }

    /** Per-artefact detail, newest first, for the flush log. */
    fun failures(): List<ArtefactOutcome> = outcomes.filterNot { it.succeeded }

    companion object {
        fun skipped(reason: String, atWallMillis: Long = 0) = FlushReport(
            startedWallMillis = atWallMillis,
            finishedWallMillis = atWallMillis,
            skippedReason = reason,
        )
    }
}

/**
 * What the room-wide ground-truth aggregate did with the scans this flush sent it.
 *
 * The aggregate is an operational convenience — it is what lets the operator console show a room
 * count rather than one handset's count — and it is explicitly **not** the science. The S3 TSV is,
 * so a failure here is reported and retried and is never allowed to affect a session upload.
 *
 * [duplicates] is a success, not a warning: idempotency is a unique index on `scan_nonce`, and
 * re-sending a session's complete set is the intended, safe behaviour of the whole-file flush.
 *
 * [conflicts] is the one number that must never be swallowed. It is pre-registration exclusion E3
 * — the same nonce arriving with a different participant, zone or direction — and by the time the
 * data is analysed the affected interval is already excluded with nobody left to ask what happened.
 */
data class TallyOutcome(
    val attempted: Int = 0,
    val accepted: Int = 0,
    val duplicates: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    /** True when the endpoint could not be reached at all; the scans stay pending. */
    val unreachable: Boolean = false,
    val wasAttempted: Boolean = true,
) {
    val acknowledged: Int get() = accepted + duplicates
    val hasConflicts: Boolean get() = conflicts > 0
    val isClean: Boolean get() = !unreachable && conflicts == 0 && rejected == 0

    val line: String?
        get() = when {
            !wasAttempted || attempted == 0 -> null
            unreachable -> "Room tally not reachable — $attempted scan(s) still queued for it. " +
                "The dataset copy is unaffected."

            isClean -> "Room tally updated: $accepted new, $duplicates already known."
            else -> "Room tally updated: $accepted new, $duplicates already known, " +
                "$conflicts CONFLICT(S), $rejected rejected. Tell the operator about conflicts."
        }

    companion object {
        val NOT_ATTEMPTED = TallyOutcome(wasAttempted = false)
    }
}

/** Rows of one artefact still waiting on this device. */
data class PendingArtefact(val artefact: String, val rows: Long)

/** One session's backlog. */
data class PendingSession(
    val sessionId: String,
    val status: String,
    val startedWallMillis: Long,
    val uploadError: String?,
    val artefacts: List<PendingArtefact>,
) {
    val rows: Long get() = artefacts.sumOf { it.rows }
    val shortId: String get() = sessionId.take(8)
}

/**
 * Everything still on the phone, per artefact.
 *
 * The point of the breakdown is that "3 sessions pending" and "3 sessions pending, 412 000 traffic
 * rows and 2 ground-truth scans" are different facts to a person deciding whether to wait for Wi-Fi.
 */
data class PendingInventory(
    val sessions: List<PendingSession> = emptyList(),
    /** Scans not yet in the S3 artefact — the science copy. */
    val groundTruthRows: Long = 0,
    /**
     * Scans not yet in the room-wide aggregate.
     *
     * Counted separately because the two destinations fail independently: a scan can be safely in
     * the dataset and still missing from the operator's live count, and an operator staring at a
     * tally that looks short needs to be able to tell which of the two it is.
     */
    val groundTruthNotInTally: Long = 0,
) {
    val sessionCount: Int get() = sessions.size
    val sessionRows: Long get() = sessions.sumOf { it.rows }
    val totalRows: Long get() = sessionRows + groundTruthRows
    val isEmpty: Boolean
        get() = sessions.isEmpty() && groundTruthRows == 0L && groundTruthNotInTally == 0L

    /** Rows per artefact name, summed across sessions — the compact form for a status card. */
    fun byArtefact(): List<PendingArtefact> {
        val totals = linkedMapOf<String, Long>()
        sessions.forEach { session ->
            session.artefacts.forEach { artefact ->
                totals[artefact.artefact] = (totals[artefact.artefact] ?: 0L) + artefact.rows
            }
        }
        if (groundTruthRows > 0) {
            totals["ground_truth.tsv"] = (totals["ground_truth.tsv"] ?: 0L) + groundTruthRows
        }
        return totals.map { PendingArtefact(it.key, it.value) }.filter { it.rows > 0 }
    }

    companion object {
        val EMPTY = PendingInventory()
    }
}
