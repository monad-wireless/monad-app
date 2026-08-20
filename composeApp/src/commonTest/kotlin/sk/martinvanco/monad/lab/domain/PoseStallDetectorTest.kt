package sk.martinvanco.monad.lab.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stall detector exists because walk B (2026-08-19) carried a twenty-second frame gap that no
 * artefact recorded: the poller skips stale frames, so a stalled tracker reads as a slow stream and
 * the delivered-rate figure smears the hole over the whole session. These tests pin the transition
 * semantics — one marker per stall, one per resumption, and a gap reported at its true width.
 */
class PoseStallDetectorTest {

    private val second = 1_000_000_000L

    @Test
    fun aQuietStreamBelowTheThresholdRaisesNothing() {
        val detector = PoseStallDetector(commandedRateHz = 10.0)
        // 10 Hz → threshold is the 2 s floor, not 300 ms.
        assertEquals(2 * second, detector.thresholdNanos)
        assertNull(detector.evaluate(nowNanos = second, lastAdvanceNanos = 0))
    }

    @Test
    fun aStallIsRaisedOnceAndCarriesItsGap() {
        val detector = PoseStallDetector(commandedRateHz = 10.0)
        val first = detector.evaluate(nowNanos = 3 * second, lastAdvanceNanos = 0)
        assertEquals(PoseStallDetector.Transition.Stalled(3 * second), first)
        // Still stalled a heartbeat later: no second marker. A stall that re-fires every second
        // would bury the marker stream under its own diagnosis.
        assertNull(detector.evaluate(nowNanos = 4 * second, lastAdvanceNanos = 0))
    }

    @Test
    fun aResumptionThroughTheHeartbeatReportsTheRemainingGap() {
        val detector = PoseStallDetector(commandedRateHz = 10.0)
        detector.evaluate(nowNanos = 3 * second, lastAdvanceNanos = 0)
        val resumed = detector.evaluate(nowNanos = 21 * second, lastAdvanceNanos = 20 * second)
        assertTrue(resumed is PoseStallDetector.Transition.Resumed)
        assertEquals(second, resumed.gapNanos)
    }

    @Test
    fun aResumptionThroughASampleReportsTheTrueGap() {
        // The sample path knows the previous advance, so the twenty-second hole is reported as
        // twenty seconds — not as whatever sub-threshold remainder the next heartbeat would see.
        val detector = PoseStallDetector(commandedRateHz = 2.0)
        detector.evaluate(nowNanos = 22 * second, lastAdvanceNanos = 2 * second)
        val resumed = detector.onAdvance(nowNanos = 22 * second, previousAdvanceNanos = 2 * second)
        assertEquals(PoseStallDetector.Transition.Resumed(20 * second), resumed)
        // And the detector is genuinely reset: a healthy stream raises nothing.
        assertNull(detector.evaluate(nowNanos = 23 * second, lastAdvanceNanos = 22 * second))
    }

    @Test
    fun anAdvanceWithoutAStallIsSilent() {
        val detector = PoseStallDetector(commandedRateHz = 10.0)
        assertNull(detector.onAdvance(nowNanos = second, previousAdvanceNanos = 0))
    }

    @Test
    fun slowRatesGetProportionallyWiderThresholds() {
        // At 1 Hz three commanded periods is 3 s, above the floor — ARKit holding a frame for one
        // beat at a slow rate is not a stall.
        assertEquals(3 * second, PoseStallDetector(commandedRateHz = 1.0).thresholdNanos)
    }
}
