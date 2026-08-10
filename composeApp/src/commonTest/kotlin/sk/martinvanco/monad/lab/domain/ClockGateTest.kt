package sk.martinvanco.monad.lab.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate G4, checked against the pre-registration's own rules.
 *
 * The two that matter operationally: **two** samples are the difference between an identifiable
 * affine fit and an offset-only fallback that flags the fold, and the recentred least-squares fit
 * has to survive monotonic nanoseconds of order 1e15 in a Double.
 */
class ClockGateTest {

    private fun sample(monoNanos: Long, offsetNanos: Long, exchanges: Int = 20) =
        ClockEstimate(
            offsetNanos = offsetNanos,
            delayNanos = 2_000_000,
            skewPpm = 0.0,
            anchorNanos = monoNanos,
            samples = exchanges,
        )

    @Test
    fun noSamplesExcludesTheFold() {
        val report = ClockGate.evaluate(emptyList())
        assertEquals(ClockGateStatus.NO_SAMPLES, report.status)
        assertTrue(report.wouldFailGate)
        assertFalse(report.meetsMinimumSamples)
    }

    @Test
    fun oneSampleIsTheOffsetOnlyFallbackAndStillFailsTheGate() {
        val report = ClockGate.evaluate(listOf(sample(1_000_000_000_000, 5_000_000)))
        assertEquals(ClockGateStatus.OFFSET_ONLY, report.status)
        assertEquals(1, report.sampleCount)
        assertFalse(report.meetsMinimumSamples)
        assertTrue(report.wouldFailGate, "the pre-registration flags an offset-only fold")
        assertEquals(5.0, report.offsetMillis, absoluteTolerance = 1e-9)
    }

    @Test
    fun twoSamplesGiveAnIdentifiableFit() {
        val report = ClockGate.evaluate(
            listOf(
                sample(1_000_000_000_000, 5_000_000),
                sample(1_600_000_000_000, 5_600_000),
            )
        )
        assertEquals(ClockGateStatus.OK, report.status)
        assertTrue(report.meetsMinimumSamples)
        assertFalse(report.wouldFailGate)
        // 600 µs of offset drift over 600 s of monotonic time is 1 ppm.
        assertEquals(1.0, report.skewPpm, absoluteTolerance = 0.05)
        assertNull(report.maxFitResidualMillis, "two points fit a line exactly — no evidence yet")
    }

    @Test
    fun theFitRecoversAKnownSkewAndOffsetAtRealisticMagnitudes() {
        // Monotonic origin of order 1e15 ns is where a naive least squares loses the signal to
        // floating-point cancellation. 12 ppm skew, 40 ms offset.
        val origin = 1_234_567_890_123_456L
        val skewPpm = 12.0
        val offsetAtOrigin = 40_000_000L
        val samples = (0..5).map { i ->
            val dt = i * 300_000_000_000L // five minutes apart
            sample(origin + dt, offsetAtOrigin + (skewPpm * 1e-6 * dt).toLong())
        }

        val fit = ClockGate.fitAffine(samples)
        assertEquals(skewPpm, fit.skewPpm, absoluteTolerance = 0.01)
        assertTrue(
            fit.maxAbsResidualNanos < 1_000.0,
            "a perfectly linear series must fit to well under a microsecond, was " +
                "${fit.maxAbsResidualNanos} ns",
        )
        // And the transform must actually place a stamp where the model says.
        val projected = fit.toReferenceNanos(origin)
        assertTrue(
            abs(projected - (origin + offsetAtOrigin)) < 1_000_000,
            "projection drifted by ${projected - (origin + offsetAtOrigin)} ns",
        )
    }

    @Test
    fun aWanderingClockShowsUpAsResidualAndFailsTheSixSecondBudget() {
        val origin = 1_000_000_000_000L
        val samples = listOf(
            sample(origin, 0),
            sample(origin + 300_000_000_000L, 0),
            // One burst measured ten seconds out. Least squares spreads an outlier across the fit,
            // so the surviving residual is ~7 s — still past G4a's one-CSI-window budget.
            sample(origin + 600_000_000_000L, 10_000_000_000L),
            sample(origin + 900_000_000_000L, 0),
        )
        val report = ClockGate.evaluate(samples)
        assertEquals(ClockGateStatus.OK, report.status)
        val residual = assertNotNull(report.maxFitResidualMillis)
        assertTrue(residual > ClockGate.BUDGET_ALL_TESTS_MILLIS, "residual was $residual ms")
        assertTrue(report.wouldFailGate)
    }

    @Test
    fun aResidualBetweenTheTwoBudgetsDropsOnlyTheChangeDetectionLeg() {
        val origin = 1_000_000_000_000L
        val samples = listOf(
            sample(origin, 0),
            sample(origin + 300_000_000_000L, 0),
            // One second out: inside G4a's 6 s, well past G4b's 250 ms.
            sample(origin + 600_000_000_000L, 1_000_000_000L),
            sample(origin + 900_000_000_000L, 0),
        )
        val report = ClockGate.evaluate(samples)
        assertFalse(report.wouldFailGate, "T1/T2/T4 keep the fold")
        assertTrue(report.wouldFailT3Only, "T3 does not")
    }

    @Test
    fun aWitnessOnlySessionIsNotApplicableRatherThanFailing() {
        val report = ClockGate.evaluate(emptyList(), applicable = false)
        assertEquals(ClockGateStatus.NOT_APPLICABLE, report.status)
        assertFalse(report.wouldFailGate, "a passive participant has not done anything wrong")
    }

    @Test
    fun burstsAtTheSameInstantProduceNoSlopeRatherThanInfinity() {
        val at = 1_000_000_000_000L
        val fit = ClockGate.fitAffine(listOf(sample(at, 1_000), sample(at, 2_000)))
        assertEquals(1.0, fit.a, absoluteTolerance = 1e-12)
        assertEquals(0.0, fit.skewPpm, absoluteTolerance = 1e-9)
    }

    @Test
    fun exchangesWithNoSuccessfulSamplesAreNotCounted() {
        val report = ClockGate.evaluate(
            listOf(ClockEstimate(0, 0, 0.0, 1_000, samples = 0))
        )
        assertEquals(ClockGateStatus.NO_SAMPLES, report.status)
    }

    @Test
    fun minimumDelayReductionKeepsTheLeastQueuedExchange() {
        // The Cristian/NTP filter: queueing delay is one-sided, so the minimum-delay exchange is
        // the least contaminated and averaging would introduce a bias rather than a wobble.
        val clean = ClockExchange(t1Nanos = 0, t2Nanos = 1_000, t3Nanos = 1_100, t4Nanos = 2_000)
        val queued = ClockExchange(t1Nanos = 0, t2Nanos = 9_000, t3Nanos = 9_100, t4Nanos = 30_000)
        val reduced = assertNotNull(ClockEstimator.fromBurst(listOf(queued, clean)))
        assertEquals(clean.offsetNanos, reduced.offsetNanos)
        assertEquals(clean.delayNanos, reduced.delayNanos)
        assertEquals(2, reduced.samples, "the count reflects the burst, the value reflects the best")
    }

    @Test
    fun skewIsNotFittedAcrossBurstsLessThanAMinuteApart() {
        val previous = ClockEstimate(
            offsetNanos = 0,
            delayNanos = 1_000,
            skewPpm = 3.5,
            anchorNanos = 1_000_000_000_000,
            samples = 20,
        )
        val soonAfter = ClockExchange(
            t1Nanos = 1_000_010_000_000,
            t2Nanos = 1_000_010_500_000,
            t3Nanos = 1_000_010_600_000,
            t4Nanos = 1_000_011_000_000,
        )
        val reduced = assertNotNull(ClockEstimator.fromBurst(listOf(soonAfter), previous))
        assertEquals(3.5, reduced.skewPpm, absoluteTolerance = 1e-9)
    }
}
