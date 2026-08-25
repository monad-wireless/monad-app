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
            // Four seconds apart: the 5 m and 4 m legs are 1.25 and 1.0 m/s, i.e. walking.
            pose(1_000_000_000, x = 0f, z = 0f),
            pose(5_000_000_000, x = 3f, z = 4f),
            pose(9_000_000_000, x = 3f, z = 8f),
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
            listOf(
                pose(1_000_000_000, x = 0f, z = 0f),
                pose(5_000_000_000, x = 5f, z = 0f),
                pose(9_000_000_000, x = 0f, z = 0f),
            )
        )
        assertEquals(10.0, summary.pathLengthMetres, 1e-6)
    }

    @Test
    fun anUnavailablePoseContributesNoJump() {
        // A disowned pose is at an arbitrary place. Counting the displacement across it would inflate
        // the length in exactly the direction that makes a broken track look like a long walk.
        val summary = PoseTrackSummary.of(
            listOf(
                pose(1_000_000_000, x = 0f, z = 0f),
                pose(2_000_000_000, x = 100f, z = 0f, quality = TrackingQuality.UNAVAILABLE),
                pose(3_000_000_000, x = 1f, z = 0f),
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
                pose(1_000_000_000, x = 0f, z = 0f),
                pose(3_000_000_000, x = 2f, z = 0f, quality = TrackingQuality.LIMITED),
            )
        )
        assertEquals(2.0, summary.pathLengthMetres, 1e-6)
        assertEquals(0.5, summary.normalFraction, 1e-9)
    }

    @Test
    fun aRelocalisationJumpIsRejectedAndCounted() {
        // Measured on two real walks, 2026-08-19. Both reported 92 m and 161 m of path for a handset
        // whose median step was 0.7 cm and 15 cm; the totals were a handful of instantaneous jumps
        // implying 21.8 and 44.5 m/s. The operator had been told to read path length as the check on
        // whether the tracker initialised, and it was the one number saying everything was fine.
        //
        // 10 m in 0.2 s is 50 m/s. No body does that, so it is ARKit re-solving its world.
        val summary = PoseTrackSummary.of(
            listOf(
                pose(0, x = 0f, z = 0f),
                pose(200_000_000, x = 1f, z = 0f),      // 1 m in 0.2 s = 5 m/s -> also a jump
                pose(400_000_000, x = 11f, z = 0f),     // 10 m in 0.2 s = 50 m/s -> a jump
                pose(1_400_000_000, x = 12f, z = 0f),   // 1 m in 1.0 s = 1 m/s -> walking
            )
        )
        assertEquals(1.0, summary.pathLengthMetres, 1e-6, "only the walkable step counts")
        assertEquals(2L, summary.rejectedJumps)
        assertEquals(11.0, summary.rejectedJumpMetres, 1e-6)
    }

    @Test
    fun aBriskWalkIsNeverRejected() {
        // The threshold is a physical prior and must not clip real movement. 1.5 m/s is a brisk
        // indoor walk; the fleet's BLE measured these same two walks at a median 0.73 m/s.
        val summary = PoseTrackSummary.of(
            listOf(pose(0, x = 0f, z = 0f), pose(1_000_000_000, x = 1.5f, z = 0f))
        )
        assertEquals(1.5, summary.pathLengthMetres, 1e-6)
        assertEquals(0L, summary.rejectedJumps)
    }

    @Test
    fun twoPosesAtOneInstantMetresApartIsAJump() {
        // A re-solve landing inside one sampling period. Admitting it would add distance for zero
        // elapsed time, which no speed threshold expressed as a ratio can catch.
        val summary = PoseTrackSummary.of(
            listOf(pose(1_000, x = 0f, z = 0f), pose(1_000, x = 5f, z = 0f))
        )
        assertEquals(0.0, summary.pathLengthMetres, 1e-9)
        assertEquals(1L, summary.rejectedJumps)
    }

    @Test
    fun verticalExtentIsWhereAFloorChangeShowsUp() {
        val summary = PoseTrackSummary.of(
            listOf(
                pose(1_000_000_000, x = 0f, z = 0f, y = 0f),
                pose(5_000_000_000, x = 0f, z = 0f, y = 3.2f),
            )
        )
        assertEquals(3.2, summary.extentYMetres, 1e-5)
    }
}

class PoseTrackProgressTest {

    @Test
    fun theRunningSumRejectsTheSameJumpsAsTheBatchReduction() {
        // The live console readout and the sidecar are two implementations of one number. If they
        // disagree the operator decides whether to re-take a walk from a figure the artefact will
        // not carry — which is how a 92 m readout accompanied a stationary handset.
        val track = listOf(
            pose(0, x = 0f, z = 0f),
            pose(1_000_000_000, x = 1f, z = 0f),
            pose(1_100_000_000, x = 30f, z = 0f),     // 29 m in 0.1 s: a jump
            pose(2_100_000_000, x = 31f, z = 0f),
        )
        val running = track.fold(PoseTrackProgress.IDLE) { acc, s -> acc.plus(s) }
        val batch = PoseTrackSummary.of(track)
        assertEquals(batch.pathLengthMetres, running.pathLengthMetres, 1e-6)
        assertEquals(batch.rejectedJumps, running.rejectedJumps)
        assertEquals(2.0, running.pathLengthMetres, 1e-6)
    }

    @Test
    fun theRunningSumAgreesWithTheBatchReduction() {
        // The invariant that matters most in this file. The console shows a **running** sum
        // accumulated pose by pose; the sidecar records a **batch** reduction over the stored rows.
        // They are two implementations of one number, and if they disagree the operator's decision to
        // keep or re-take a walk is made against a figure the artefact will not carry.
        // One-second spacing, so every real step is inside the locomotion prior and the only
        // exclusion under test is the UNAVAILABLE one.
        val track = listOf(
            pose(1_000_000_000, x = 0f, z = 0f),
            pose(2_000_000_000, x = 1f, z = 0f),
            pose(3_000_000_000, x = 50f, z = 0f, quality = TrackingQuality.UNAVAILABLE),
            pose(4_000_000_000, x = 1f, z = 3f, quality = TrackingQuality.LIMITED),
            pose(5_000_000_000, x = 1f, z = 5f),
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
            .plus(pose(1_000_000_000, x = 0f, z = 0f, quality = TrackingQuality.LIMITED))
            .plus(pose(3_000_000_000, x = 1f, z = 0f))
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
                room = "library-open",
                targetKind = "card",
            ),
        )
        // v2 (IP-140) adds `room` and `target_kind`. The version moved rather than the fields
        // being added quietly, because their ABSENCE changes meaning: a v2 payload with a null
        // target_kind says "recorded outside a probe, so nothing asserted a kind", while a v1
        // payload says "the build that wrote this could not express one". A reader that treats
        // the two alike counts every pre-IP-140 waypoint as an untagged probe.
        assertTrue(encoded.contains("\"schema\":\"monad-app/waypoint-marker/v2\""), encoded)
        assertTrue(encoded.contains("\"code\":\"MONAD-FP-07\""), encoded)
        assertTrue(encoded.contains("\"pose_mono_ns\":1234"), encoded)
        assertTrue(encoded.contains("\"quality\":\"normal\""), encoded)
        assertTrue(encoded.contains("\"room\":\"library-open\""), encoded)
        assertTrue(encoded.contains("\"target_kind\":\"card\""), encoded)

        val decoded = json.decodeFromString(WaypointMarkerPayload.serializer(), encoded)
        assertEquals("MONAD-FP-07", decoded.code)
        assertEquals(1.5f, decoded.pose?.x)
        assertEquals(-2.25f, decoded.pose?.z)
        assertEquals("library-open", decoded.room)
        assertEquals("card", decoded.targetKind)
    }

    @Test
    fun aWaypointFromTheWalkConsoleAssertsNoRoomAndNoKind() {
        // The console has no quest to assert either, so both are null — and null must decode as
        // null rather than as an empty string, so "nothing asserted this" stays distinguishable
        // from "asserted to be blank".
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val encoded = json.encodeToString(
            WaypointMarkerPayload.serializer(),
            WaypointMarkerPayload(code = "MONAD-FP-13"),
        )
        val decoded = json.decodeFromString(WaypointMarkerPayload.serializer(), encoded)
        assertEquals(null, decoded.room)
        assertEquals(null, decoded.targetKind)
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
