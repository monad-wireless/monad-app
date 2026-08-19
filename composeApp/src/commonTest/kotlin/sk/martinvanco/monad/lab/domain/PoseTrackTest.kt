package sk.martinvanco.monad.lab.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pose track's arithmetic, pinned where it can be pinned without a camera.
 *
 * Three things are tested here and each one is a defect that would be invisible in the field:
 *
 *  * the matrix-to-quaternion branch, whose naive form divides by zero for a phone held pointing
 *    backwards along the walk — a 180° rotation, which is the second half of every out-and-back,
 *  * the path-length reduction's treatment of untracked samples, which decides whether a broken
 *    tracker reports a long walk or a short one,
 *  * the agreement between the **running** sum the console shows and the **batch** reduction the
 *    sidecar records, which are two implementations of one number.
 */
class PoseGeometryTest {

    @Test
    fun identityIsTheIdentityQuaternion() {
        val q = PoseGeometry.quaternion(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f),
        )
        assertEquals(0f, q[0], TOLERANCE)
        assertEquals(0f, q[1], TOLERANCE)
        assertEquals(0f, q[2], TOLERANCE)
        assertEquals(1f, q[3], TOLERANCE)
    }

    @Test
    fun aHalfTurnDoesNotDivideByZero() {
        // The trap. Trace of a 180° rotation about y is -1, so `sqrt(trace + 1)` is zero and the
        // w-first formula divides by it. This is a phone pointing the other way down the same
        // corridor, which happens on every out-and-back walk.
        val q = PoseGeometry.quaternion(
            floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, -1f),
        )
        assertTrue(q.all { it.isFinite() }, "a half turn produced ${q.toList()}")
        assertEquals(1f, norm(q), TOLERANCE)
        // 180° about y: (x, y, z, w) = (0, ±1, 0, 0). Sign is free — a quaternion and its negation
        // are the same rotation — so the magnitudes are what is asserted.
        assertEquals(1f, abs(q[1]), TOLERANCE)
        assertEquals(0f, abs(q[3]), TOLERANCE)
    }

    @Test
    fun everyAxisHalfTurnStaysUnit() {
        // All three of Shanks's branches, one per axis, because a wrong branch produces a finite
        // quaternion that is simply the wrong rotation — no crash, no NaN, just a heading that is
        // silently off by ninety degrees for the whole walk.
        val halfTurns = listOf(
            Triple(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, -1f)),
            Triple(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, -1f)),
            Triple(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f)),
        )
        halfTurns.forEachIndexed { axis, (c0, c1, c2) ->
            val q = PoseGeometry.quaternion(c0, c1, c2)
            assertEquals(1f, norm(q), TOLERANCE, "axis $axis produced ${q.toList()}")
            assertEquals(1f, abs(q[axis]), TOLERANCE, "axis $axis produced ${q.toList()}")
        }
    }

    @Test
    fun aQuarterTurnAboutYawIsRecovered() {
        // 90° about y maps x to -z and z to x.
        val q = PoseGeometry.quaternion(
            floatArrayOf(0f, 0f, -1f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(1f, 0f, 0f),
        )
        assertEquals(1f, norm(q), TOLERANCE)
        val half = 0.70710678f
        assertEquals(half, abs(q[1]), TOLERANCE)
        assertEquals(half, abs(q[3]), TOLERANCE)
    }

    private fun norm(q: FloatArray): Float =
        kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}

class PoseTrackSummaryTest {

    @Test
    fun anEmptyTrackIsNotAZeroMetreWalk() {
        // EMPTY, not a summary of zero samples with a path length: a reader has to be able to tell
        // "nothing recorded" from "recorded, went nowhere", and only the first is a broken tracker.
        assertEquals(PoseTrackSummary.EMPTY, PoseTrackSummary.of(emptyList()))
        assertEquals(0L, PoseTrackSummary.EMPTY.samples)
    }

    @Test
    fun pathLengthSumsConsecutiveDisplacements() {
        val track = listOf(
            pose(1, x = 0f, z = 0f),
            pose(2, x = 3f, z = 4f),
            pose(3, x = 3f, z = 8f),
        )
        val summary = PoseTrackSummary.of(track)
        assertEquals(9.0, summary.pathLengthMetres, 1e-6)
        assertEquals(3L, summary.samples)
        assertEquals(1.0, summary.normalFraction, 1e-9)
        assertEquals(3.0, summary.extentXMetres, 1e-6)
        assertEquals(8.0, summary.extentZMetres, 1e-6)
    }

    @Test
    fun pathLengthIsNotTheStraightLineDistance() {
        // Out and back. A straight-line metric would say zero and a walk that covered ten metres
        // would look like a tracker that never moved — the exact failure the readout exists to catch.
        val summary = PoseTrackSummary.of(
            listOf(pose(1, x = 0f, z = 0f), pose(2, x = 5f, z = 0f), pose(3, x = 0f, z = 0f))
        )
        assertEquals(10.0, summary.pathLengthMetres, 1e-6)
    }

    @Test
    fun anUnavailablePoseContributesNoJump() {
        // A disowned pose is at an arbitrary place. Counting the displacement across it would inflate
        // the length in exactly the direction that makes a broken track look like a long walk.
        val summary = PoseTrackSummary.of(
            listOf(
                pose(1, x = 0f, z = 0f),
                pose(2, x = 100f, z = 0f, quality = TrackingQuality.UNAVAILABLE),
                pose(3, x = 1f, z = 0f),
            )
        )
        assertEquals(0.0, summary.pathLengthMetres, 1e-9)
        assertEquals(3L, summary.samples)
        // Still counted as a sample, and still outside the trusted fraction. Dropping the row would
        // hide that the tracker lost itself at all.
        assertEquals(2.0 / 3.0, summary.normalFraction, 1e-9)
    }

    @Test
    fun aLimitedPoseStillContributesItsDisplacement() {
        // LIMITED means "do not trust this", not "this is nowhere". The window is excluded by the
        // quality column downstream, not by silently shortening the track here.
        val summary = PoseTrackSummary.of(
            listOf(
                pose(1, x = 0f, z = 0f),
                pose(2, x = 2f, z = 0f, quality = TrackingQuality.LIMITED),
            )
        )
        assertEquals(2.0, summary.pathLengthMetres, 1e-6)
        assertEquals(0.5, summary.normalFraction, 1e-9)
    }

    @Test
    fun verticalExtentIsWhereAFloorChangeShowsUp() {
        val summary = PoseTrackSummary.of(
            listOf(pose(1, x = 0f, z = 0f, y = 0f), pose(2, x = 0f, z = 0f, y = 3.2f))
        )
        assertEquals(3.2, summary.extentYMetres, 1e-5)
    }
}

class PoseTrackProgressTest {

    @Test
    fun theRunningSumAgreesWithTheBatchReduction() {
        // The invariant that matters most in this file. The console shows a **running** sum
        // accumulated pose by pose; the sidecar records a **batch** reduction over the stored rows.
        // They are two implementations of one number, and if they disagree the operator's decision to
        // keep or re-take a walk is made against a figure the artefact will not carry.
        val track = listOf(
            pose(1, x = 0f, z = 0f),
            pose(2, x = 1f, z = 0f),
            pose(3, x = 50f, z = 0f, quality = TrackingQuality.UNAVAILABLE),
            pose(4, x = 1f, z = 3f, quality = TrackingQuality.LIMITED),
            pose(5, x = 1f, z = 7f),
        )
        val running = track.fold(PoseTrackProgress.IDLE) { acc, sample -> acc.plus(sample) }
        val batch = PoseTrackSummary.of(track)

        assertEquals(batch.pathLengthMetres, running.pathLengthMetres, 1e-6)
        assertEquals(batch.samples, running.samples)
        assertEquals(batch.normalFraction, running.normalFraction!!, 1e-9)
    }

    @Test
    fun anUnstartedTrackHasAnUnknownTrustedFractionRatherThanZero() {
        // Zero per cent trusted reads as "the tracker is failing". Before the first pose the honest
        // answer is that nothing is known yet.
        assertNull(PoseTrackProgress.IDLE.normalFraction)
        assertEquals(TrackingQuality.UNAVAILABLE, PoseTrackProgress.IDLE.quality)
    }

    @Test
    fun qualityIsTheLatestPoseNotTheWorstOne() {
        // The console's colour has to answer "is it tracking *now*", because that is the question an
        // operator can act on while still holding the phone. Worst-state accounting is the health
        // monitor's job and is reported separately.
        val progress = PoseTrackProgress.IDLE
            .plus(pose(1, x = 0f, z = 0f, quality = TrackingQuality.LIMITED))
            .plus(pose(2, x = 1f, z = 0f))
        assertEquals(TrackingQuality.NORMAL, progress.quality)
    }
}

class WaypointMarkerPayloadTest {

    @Test
    fun theWireNamesAreTheContract() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val encoded = json.encodeToString(
            WaypointMarkerPayload.serializer(),
            WaypointMarkerPayload(
                code = "MONAD-FP-07",
                note = "by the far window",
                pose = WaypointPose.of(pose(1_234, x = 1.5f, z = -2.25f)),
            ),
        )
        assertTrue(encoded.contains("\"schema\":\"monad-app/waypoint-marker/v1\""), encoded)
        assertTrue(encoded.contains("\"code\":\"MONAD-FP-07\""), encoded)
        assertTrue(encoded.contains("\"pose_mono_ns\":1234"), encoded)
        assertTrue(encoded.contains("\"quality\":\"normal\""), encoded)

        val decoded = json.decodeFromString(WaypointMarkerPayload.serializer(), encoded)
        assertEquals("MONAD-FP-07", decoded.code)
        assertEquals(1.5f, decoded.pose?.x)
        assertEquals(-2.25f, decoded.pose?.z)
    }

    @Test
    fun aWaypointWithNoTrackerIsStillAWaypoint() {
        // Null pose is a real answer: the waypoint fixes a time to a place even on a handset with no
        // odometry. It simply cannot anchor a trajectory, and a reader has to tell the two apart.
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val encoded = json.encodeToString(
            WaypointMarkerPayload.serializer(),
            WaypointMarkerPayload(code = "MONAD-A-IN"),
        )
        val decoded = json.decodeFromString(WaypointMarkerPayload.serializer(), encoded)
        assertNull(decoded.pose)
        assertEquals("MONAD-A-IN", decoded.code)
    }
}

private fun pose(
    monotonicNanos: Long,
    x: Float,
    z: Float,
    y: Float = 0f,
    quality: TrackingQuality = TrackingQuality.NORMAL,
) = PoseSample(
    monotonicNanos = monotonicNanos,
    wallMillis = monotonicNanos,
    x = x,
    y = y,
    z = z,
    qx = 0f,
    qy = 0f,
    qz = 0f,
    qw = 1f,
    quality = quality,
)
