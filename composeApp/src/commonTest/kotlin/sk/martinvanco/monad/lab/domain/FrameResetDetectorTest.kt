package sk.martinvanco.monad.lab.domain

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The detector exists because the 2026-08-28 survey walk changed coordinate system 26 minutes in and
 * nothing recorded it: both samples either side were `normal`, no platform callback fired, and the
 * six waypoints that followed were fitted against the thirty-one before them. The scale ratio came
 * out at 1.21 and the 16.75 m residual on one fleet node was written up as a mis-scanned sticker.
 *
 * These tests pin the two rules that separate a reset from the two things it resembles: a transient
 * glitch, and the tail of a reset already reported. Both were measured on that walk.
 */
class FrameResetDetectorTest {

    private val tenth = 100_000_000L

    /** A pose at a place and an instant. Quality is `NORMAL` unless a test is about the exception. */
    private fun pose(
        atNanos: Long,
        x: Double,
        z: Double,
        quality: TrackingQuality = TrackingQuality.NORMAL,
    ) = PoseSample(
        monotonicNanos = atNanos,
        wallMillis = atNanos / 1_000_000,
        x = x.toFloat(),
        y = 0f,
        z = z.toFloat(),
        qx = 0f,
        qy = 0f,
        qz = 0f,
        qw = 1f,
        quality = quality,
    )

    /** Feed a walk at 10 Hz, collecting whatever the detector confirmed. */
    private fun run(track: List<Pair<Double, Double>>): List<FrameResetDetector.Reset> {
        val detector = FrameResetDetector()
        val out = mutableListOf<FrameResetDetector.Reset>()
        track.forEachIndexed { index, (x, z) ->
            detector.onPose(pose(index * tenth, x, z))?.let { out += it }
        }
        detector.flush()?.let { out += it }
        return out
    }

    @Test
    fun awalkThatHoldsOneFrameRaisesNothing() {
        val steady = (0 until 60).map { it * 0.12 to 0.0 }
        assertEquals(emptyList(), run(steady))
    }

    @Test
    fun anOriginResetIsReportedOnceWithItsDisplacementAndGap() {
        // The measured shape: the pose jumps 15.12 m to a new origin and stays there.
        val before = (0 until 20).map { 0.0 to 0.0 }
        val after = (0 until 60).map { 15.12 to 0.0 }
        val resets = run(before + after)

        assertEquals(1, resets.size)
        val reset = resets.single()
        assertEquals(15.12, reset.displacementMetres, 1e-6)
        assertEquals(20 * tenth, reset.atMonotonicNanos, "the marker must land on the jump")
        assertEquals(0.1, reset.gapSeconds, 1e-9)
    }

    @Test
    fun theSampleAfterAResetIsNotASecondReset() {
        // The tail of the event already reported. On the real walk the sample straight after the
        // 15.12 m jump also exceeded 3 m/s, and counting it would have raised a second reset out of
        // the first one's shadow.
        val before = (0 until 20).map { 0.0 to 0.0 }
        val after = listOf(15.12 to 0.0, 15.45 to 0.0) + (0 until 60).map { 15.45 + it * 0.12 to 0.0 }
        assertEquals(1, run(before + after).size)
    }

    @Test
    fun aGlitchThatReturnsIsNotAReset() {
        // Measured: the pose was thrown out by (+7.59, -8.40) and pulled back by (-7.59, +8.40)
        // 1.9 s later, summing to a millimetre. That is one frame with a hole, not two frames, and
        // splitting it would have cost eleven anchors.
        val before = (0 until 20).map { 0.0 to 0.0 }
        val excursion = (0 until 19).map { 7.59 to -8.40 }
        val after = (0 until 60).map { 0.0 to 0.0 }
        assertEquals(emptyList(), run(before + excursion + after))
    }

    @Test
    fun aDisplacementInsideThePhoneOffsetBudgetIsNotAReset() {
        // Below a metre the two frames are interchangeable at the accuracy any walk-derived position
        // claims, so splitting there costs anchors and buys nothing.
        val before = (0 until 20).map { 0.0 to 0.0 }
        val after = (0 until 60).map { 0.6 to 0.0 }
        assertEquals(emptyList(), run(before + after))
    }

    @Test
    fun aResetIsHeldUntilItStandsAndThenReleased() {
        val detector = FrameResetDetector()
        (0 until 20).forEach { assertNull(detector.onPose(pose(it * tenth, 0.0, 0.0))) }
        // The jump itself confirms nothing: a displacement has to stand before it is a reset.
        assertNull(detector.onPose(pose(20 * tenth, 15.12, 0.0)))
        var confirmed: FrameResetDetector.Reset? = null
        (21 until 60).forEach { index ->
            detector.onPose(pose(index * tenth, 15.12, 0.0))?.let { confirmed = it }
        }
        val reset = assertNotNull(confirmed, "the hold must release once the window has passed")
        // Released no earlier than the confirmation window, and stamped at the jump regardless.
        assertEquals(20 * tenth, reset.atMonotonicNanos)
    }

    @Test
    fun aResetInTheFinalSecondsIsReleasedAtClose() {
        // Without the flush the hold would swallow exactly the case where the operator has stopped
        // walking and is about to stop the session — and the sidecar, which replays this detector
        // over the finished list, would count a frame the timeline never named.
        val before = (0 until 20).map { 0.0 to 0.0 }
        val after = (0 until 3).map { 15.12 to 0.0 }
        assertEquals(1, run(before + after).size)
    }

    @Test
    fun aPoseThePlatformDisownedNeitherConfirmsNorCancels() {
        val detector = FrameResetDetector()
        (0 until 20).forEach { detector.onPose(pose(it * tenth, 0.0, 0.0)) }
        detector.onPose(pose(20 * tenth, 15.12, 0.0))
        // An UNAVAILABLE sample is not evidence in either direction, so it cannot release the hold —
        // and neither can the pose straight after it, because the step between them is measured
        // FROM a position the platform disowned. Confirmation resumes on the first usable pair.
        assertNull(detector.onPose(pose(60 * tenth, 15.12, 0.0, TrackingQuality.UNAVAILABLE)))
        assertNull(detector.onPose(pose(61 * tenth, 15.12, 0.0)))
        assertNotNull(detector.onPose(pose(62 * tenth, 15.12, 0.0)))
    }

    @Test
    fun theSummaryCountsWhatTheDetectorFinds() {
        // The sidecar and the timeline are two readings of one fact and must not diverge: the
        // summary replays this same detector over the finished list.
        val samples = ((0 until 20).map { 0.0 to 0.0 } + (0 until 60).map { 15.12 to 0.0 })
            .mapIndexed { index, (x, z) -> pose(index * tenth, x, z) }
        val summary = PoseTrackSummary.of(samples)
        assertEquals(1, summary.frameResets)
        // And the reset stays out of the path length, exactly as a rejected jump does.
        assertEquals(0, summary.pathLengthMetres.roundToInt())
    }
}
