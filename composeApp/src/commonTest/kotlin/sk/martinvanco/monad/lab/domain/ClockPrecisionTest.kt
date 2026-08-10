package sk.martinvanco.monad.lab.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The NTP-style estimator, and the per-marker precision stamp it feeds.
 *
 * Two properties are being defended here, and they pull in opposite directions on purpose:
 *
 * 1. **The fit is cleaned.** A burst that queued carries a one-sided offset bias, and fitting
 *    through it drags the whole line — which every block boundary is then stamped with.
 * 2. **The evidence is not.** The residual is still measured over *every* sample, so a clock that
 *    genuinely wandered cannot be filtered into looking clean. Gate G4 exists to catch exactly that.
 */
class ClockPrecisionTest {

    private fun sample(
        monoNanos: Long,
        offsetNanos: Long,
        delayNanos: Long = 2_000_000,
        exchanges: Int = 20,
    ) = ClockEstimate(
        offsetNanos = offsetNanos,
        delayNanos = delayNanos,
        skewPpm = 0.0,
        anchorNanos = monoNanos,
        samples = exchanges,
    )

    private val origin = 1_000_000_000_000L
    private val fiveMinutes = 300_000_000_000L

    @Test
    fun aCongestedBurstIsKeptOutOfTheFit() {
        // Four clean bursts on a 10 ppm line, plus one that queued for 80 ms and came back 40 ms
        // out. Fitting through it would tilt the line; the low-delay clique refuses it.
        val skewPpm = 10.0
        val clean = (0..4).filter { it != 2 }.map { i ->
            val dt = i * fiveMinutes
            sample(origin + dt, (skewPpm * 1e-6 * dt).toLong())
        }
        val congested = sample(
            monoNanos = origin + 2 * fiveMinutes,
            offsetNanos = (skewPpm * 1e-6 * 2 * fiveMinutes).toLong() + 40_000_000L,
            delayNanos = 80_000_000,
        )
        val fit = ClockGate.fitAffine((clean + congested).sortedBy { it.anchorNanos })

        assertEquals(1, fit.rejectedSamples, "the queued burst is not fitted through")
        assertEquals(skewPpm, fit.skewPpm, absoluteTolerance = 0.2)
        assertTrue(
            fit.maxAbsResidualFilteredNanos < 1_000_000.0,
            "the clean subset lies on a line: ${fit.maxAbsResidualFilteredNanos} ns",
        )
        // …and the outlier is still counted as evidence.
        assertTrue(
            fit.maxAbsResidualNanos > 30_000_000.0,
            "the rejected sample must still count against the gate, was ${fit.maxAbsResidualNanos}",
        )
    }

    @Test
    fun filteringNeverHidesAWanderingClockFromTheGate() {
        // The same series as the wandering-clock case, but now with the filter in place. If the
        // filter were allowed to clean the evidence as well as the fit, this session would report
        // itself healthy — which is precisely the failure G4 exists to catch.
        val samples = listOf(
            sample(origin, 0),
            sample(origin + fiveMinutes, 0),
            sample(origin + 2 * fiveMinutes, 10_000_000_000L),
            sample(origin + 3 * fiveMinutes, 0),
        )
        val report = ClockGate.evaluate(samples)
        val residual = assertNotNull(report.maxFitResidualMillis)
        assertTrue(residual > ClockGate.BUDGET_ALL_TESTS_MILLIS, "residual was $residual ms")
        assertTrue(report.wouldFailGate)
        assertTrue(!report.meetsAllTestsBudget)
        assertTrue(!report.meetsT3Budget)
    }

    @Test
    fun aLinkWhereEveryBurstQueuedStillGetsAFit() {
        // The dispersion cut must never leave fewer samples than the fit needs: a uniformly
        // congested link is still worth an offset, and reporting NO_SAMPLES for a session that has
        // them would be a worse lie than a wide interval.
        val samples = (0..3).map { i ->
            sample(origin + i * fiveMinutes, 0, delayNanos = 500_000_000L * (i + 1))
        }
        val report = ClockGate.evaluate(samples)
        assertEquals(ClockGateStatus.OK, report.status)
        assertNotNull(report.fit)
    }

    @Test
    fun theTwoBudgetsAreReportedSeparately() {
        // 1 s out: inside G4a's 6 s window, five times past G4b's 250 ms. Block boundaries feed T3,
        // so they live or die on the second number — and the operator must be able to see which.
        val samples = listOf(
            sample(origin, 0),
            sample(origin + fiveMinutes, 0),
            sample(origin + 2 * fiveMinutes, 1_000_000_000L),
            sample(origin + 3 * fiveMinutes, 0),
        )
        val report = ClockGate.evaluate(samples)
        assertTrue(report.meetsAllTestsBudget, "G4a: the fold survives T1/T2/T4")
        assertTrue(!report.meetsT3Budget, "G4b: T3 drops it")
        assertTrue(report.wouldFailT3Only)
    }

    @Test
    fun aTightClockSatisfiesBothBudgets() {
        val samples = (0..4).map { sample(origin + it * fiveMinutes, it * 3_000L) }
        val report = ClockGate.evaluate(samples)
        assertTrue(report.meetsAllTestsBudget)
        assertTrue(report.meetsT3Budget)
        assertTrue(report.precisionLine.contains("ms"), report.precisionLine)
    }

    // ---- the per-marker stamp -------------------------------------------------------------

    @Test
    fun aStampProjectsTheMarkedInstantOntoTheCollectorTimeline() {
        val skewPpm = 8.0
        val samples = (0..3).map { i ->
            val dt = i * fiveMinutes
            sample(origin + dt, 25_000_000L + (skewPpm * 1e-6 * dt).toLong())
        }
        val at = origin + 4 * fiveMinutes
        val stamp = ClockGate.stamp(at, samples)

        assertEquals(ClockEstimateSource.AFFINE_FIT, stamp.estimateSource)
        val projected = assertNotNull(stamp.estimatedReferenceNanos)
        val expected = at + 25_000_000L + (skewPpm * 1e-6 * 4 * fiveMinutes).toLong()
        assertTrue(
            abs(projected - expected) < 5_000_000L,
            "projection was off by ${projected - expected} ns",
        )
    }

    @Test
    fun aStampRecordsHowStaleTheSyncWasAtTheMarkedInstant() {
        // The whole reason a boundary sync exists: an edge marked seconds after a burst is better
        // placed than one marked ten minutes after, and the analysis must be able to tell them
        // apart rather than trusting both alike.
        val samples = listOf(sample(origin, 0), sample(origin + fiveMinutes, 0))
        val fresh = ClockGate.stamp(origin + fiveMinutes + 120_000_000L, samples)
        val stale = ClockGate.stamp(origin + fiveMinutes + 600_000_000_000L, samples)

        assertEquals(120L, fresh.syncAgeMillis)
        assertEquals(600_000L, stale.syncAgeMillis)
    }

    @Test
    fun aStampWithOneSampleFallsBackToOffsetOnlyAndFailsBothBudgets() {
        val stamp = ClockGate.stamp(origin + fiveMinutes, listOf(sample(origin, 5_000_000)))
        assertEquals(ClockGateStatus.OFFSET_ONLY, stamp.status)
        assertEquals(ClockEstimateSource.OFFSET_ONLY, stamp.estimateSource)
        assertNotNull(stamp.estimatedReferenceNanos)
        assertTrue(!stamp.meetsAllTestsBudget)
        assertTrue(!stamp.meetsT3Budget)
    }

    @Test
    fun aStampWithNoSyncAtAllOffersNoProjection() {
        val stamp = ClockGate.stamp(origin, emptyList())
        assertEquals(ClockGateStatus.NO_SAMPLES, stamp.status)
        assertEquals(ClockEstimateSource.NONE, stamp.estimateSource)
        assertEquals(null, stamp.estimatedReferenceNanos)
    }

    @Test
    fun aWitnessOnlySessionIsNotJudgedOnAClockItCannotHave() {
        val stamp = ClockGate.stamp(origin, emptyList(), applicable = false)
        assertEquals(ClockGateStatus.NOT_APPLICABLE, stamp.status)
        // Not applicable is not a pass: nothing from this phone is going onto the fleet timeline,
        // so a boundary from it carries no G4 claim either way.
        assertTrue(stamp.meetsAllTestsBudget, "the participant has not done anything wrong")
    }

    @Test
    fun theStampCountsRejectedSamplesSoAnalysisCanSeeTheFilterWorked() {
        val clean = (0..3).map { sample(origin + it * fiveMinutes, 0) }
        val congested = sample(origin + 4 * fiveMinutes, 40_000_000L, delayNanos = 90_000_000)
        val stamp = ClockGate.stamp(origin + 5 * fiveMinutes, clean + congested)
        assertEquals(1, stamp.samplesRejected)
    }
}
