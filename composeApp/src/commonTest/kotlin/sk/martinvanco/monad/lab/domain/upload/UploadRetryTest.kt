package sk.martinvanco.monad.lab.domain.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {

    @Test
    fun theFirstAttemptIsImmediate() {
        assertEquals(0L, RetryPolicy.LAB.delayBeforeMillis(1))
    }

    @Test
    fun delaysGrowGeometricallyAndThenStop() {
        val policy = RetryPolicy(maxAttempts = 5, baseDelayMillis = 1_000, factor = 3.0, maxDelayMillis = 10_000)
        assertEquals(0L, policy.delayBeforeMillis(1))
        assertEquals(1_000L, policy.delayBeforeMillis(2))
        assertEquals(3_000L, policy.delayBeforeMillis(3))
        assertEquals(9_000L, policy.delayBeforeMillis(4))
        assertEquals(10_000L, policy.delayBeforeMillis(5), "capped, not 27 s")
    }

    @Test
    fun theBudgetIsBoundedSoAPocketedPhoneDoesNotRetryForAnHour() {
        // A phone on the experiment AP has no route to the internet for most of a session. The
        // whole retry budget must cost seconds, not minutes, because the data is safe either way.
        assertTrue(
            RetryPolicy.LAB.worstCaseWaitMillis < 30_000,
            "worst case was ${RetryPolicy.LAB.worstCaseWaitMillis} ms",
        )
    }

    @Test
    fun shouldRetryStopsAtTheAttemptCap() {
        val policy = RetryPolicy(maxAttempts = 3)
        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(2))
        assertFalse(policy.shouldRetry(3))
        assertFalse(policy.shouldRetry(9))
    }

    @Test
    fun aSingleAttemptPolicyIsValidAndNeverRetries() {
        val policy = RetryPolicy(maxAttempts = 1)
        assertFalse(policy.shouldRetry(1))
        assertEquals(0L, policy.worstCaseWaitMillis)
    }
}

class FlushReportTest {

    private fun ok(artefact: String, rows: Long = 10, attempts: Int = 1) =
        ArtefactOutcome("s", artefact, rows, 100, attempts, succeeded = true)

    private fun failed(artefact: String, rows: Long = 10) =
        ArtefactOutcome("s", artefact, rows, 100, 4, succeeded = false, error = "timeout")

    @Test
    fun nothingToSendIsNotTheSameAsEverythingFailed() {
        // The old API returned an Int for both, and they call for opposite actions.
        val idle = FlushReport(sessionsAttempted = 0)
        val broken = FlushReport(sessionsAttempted = 1, sessionsUploaded = 0, outcomes = listOf(failed("traffic.tsv")))

        assertTrue(idle.didNothing)
        assertFalse(broken.didNothing)
        assertEquals(0, idle.artefactsFailed)
        assertEquals(1, broken.artefactsFailed)
        assertTrue(idle.headline.contains("Nothing to send"))
        assertTrue(broken.headline.contains("failed"))
    }

    @Test
    fun nothingIsEverDiscarded() {
        val report = FlushReport(
            sessionsAttempted = 2,
            sessionsUploaded = 1,
            outcomes = listOf(ok("traffic.tsv"), failed("beacons.tsv")),
        )
        assertEquals(0, report.discarded)
        assertTrue(report.headline.contains("Nothing was discarded"))
    }

    @Test
    fun retriesAreCountedSoAFlakyLinkIsVisible() {
        val report = FlushReport(outcomes = listOf(ok("a", attempts = 1), ok("b", attempts = 3)))
        assertEquals(2, report.retriesUsed)
    }

    @Test
    fun rowsAndBytesCountOnlyWhatActuallyLanded() {
        val report = FlushReport(outcomes = listOf(ok("a", rows = 500), failed("b", rows = 900)))
        assertEquals(500L, report.rowsSent)
        assertEquals(1, report.artefactsSucceeded)
    }

    @Test
    fun skippedCarriesItsReasonRatherThanLookingLikeSuccess() {
        val report = FlushReport.skipped("you are not signed in")
        assertFalse(report.isClean)
        assertTrue(report.headline.contains("not signed in"))
    }
}

class TallyOutcomeTest {

    @Test
    fun duplicatesAreSuccessNotWarning() {
        // Idempotency is a unique index on scan_nonce, and re-sending a session's complete set is
        // the intended behaviour of the whole-file flush.
        val outcome = TallyOutcome(attempted = 12, accepted = 2, duplicates = 10)
        assertTrue(outcome.isClean)
        assertEquals(12, outcome.acknowledged)
        assertFalse(outcome.hasConflicts)
    }

    @Test
    fun conflictsAreSurfacedInTheHeadline() {
        // Pre-registration exclusion E3: by analysis time the affected interval is already gone,
        // so this has to be visible while somebody can still be asked what happened.
        val outcome = TallyOutcome(attempted = 5, accepted = 4, conflicts = 1)
        assertTrue(outcome.hasConflicts)
        assertFalse(outcome.isClean)
        val line = outcome.line ?: ""
        assertTrue(line.contains("CONFLICT"), "conflicts must not be swallowed: '$line'")

        val report = FlushReport(outcomes = emptyList(), tally = outcome)
        assertTrue(report.headline.contains("CONFLICT"))
        assertFalse(report.isClean)
    }

    @Test
    fun anUnreachableAggregateDoesNotImplyDataLoss() {
        val outcome = TallyOutcome(attempted = 7, unreachable = true)
        val line = outcome.line ?: ""
        assertTrue(line.contains("still queued"))
        assertTrue(line.contains("dataset copy is unaffected"))
    }

    @Test
    fun anUnattemptedAggregateAddsNothingToTheHeadline() {
        val report = FlushReport(
            sessionsAttempted = 1,
            sessionsUploaded = 1,
            outcomes = listOf(ArtefactOutcome("s", "traffic.tsv", 3, 30, 1, true)),
        )
        assertEquals(null, report.tally.line)
        assertTrue(report.isClean)
    }
}

class PendingInventoryTest {

    @Test
    fun artefactTotalsAreSummedAcrossSessionsAndIncludeGroundTruth() {
        val inventory = PendingInventory(
            sessions = listOf(
                PendingSession("a", "closed", 1, null, listOf(PendingArtefact("traffic.tsv", 100))),
                PendingSession("b", "failed", 2, "timeout", listOf(PendingArtefact("traffic.tsv", 50))),
            ),
            groundTruthRows = 4,
        )
        val byArtefact = inventory.byArtefact().associate { it.artefact to it.rows }
        assertEquals(150L, byArtefact["traffic.tsv"])
        assertEquals(4L, byArtefact["ground_truth.tsv"])
        assertEquals(154L, inventory.totalRows)
        assertFalse(inventory.isEmpty)
    }

    @Test
    fun emptyArtefactsAreNotListed() {
        val inventory = PendingInventory(
            sessions = listOf(
                PendingSession("a", "closed", 1, null, listOf(PendingArtefact("markers.tsv", 0)))
            )
        )
        assertTrue(inventory.byArtefact().isEmpty(), "a zero-row artefact is noise on a status card")
    }

    @Test
    fun scansMissingOnlyFromTheAggregateStillCountAsPending() {
        // The two destinations fail independently, and an operator staring at a short tally needs
        // to be able to tell which one is behind.
        val inventory = PendingInventory(groundTruthRows = 0, groundTruthNotInTally = 3)
        assertFalse(inventory.isEmpty)
    }
}
