package sk.martinvanco.monad.lab.domain

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Camera pitch from the pose quaternion — the number that separated the two 2026-08-19 walks
 * (median −39° carried console-first at 35 % normal tracking, −14° at 61 %). The convention under
 * test: the camera looks along the device's −z axis, the frame is gravity-aligned with +y up, so
 * pitch is the elevation of the look vector above the horizon.
 */
class CameraPitchTest {

    @Test
    fun identityOrientationLooksAtTheHorizon() {
        // No rotation: −z is horizontal.
        assertEquals(0.0, PoseGeometry.cameraPitchDegrees(0f, 0f, 0f, 1f), 1e-6)
    }

    @Test
    fun pitchingTheDeviceDownPointsTheCameraAtTheFloor() {
        // −90° about x: quaternion (sin(−45°), 0, 0, cos(45°)). The look vector goes from −z to −y,
        // which is straight down.
        val s = (sqrt(2.0) / 2).toFloat()
        assertEquals(-90.0, PoseGeometry.cameraPitchDegrees(-s, 0f, 0f, s), 0.1)
    }

    @Test
    fun pitchingTheDeviceUpPointsTheCameraAtTheCeiling() {
        val s = (sqrt(2.0) / 2).toFloat()
        assertEquals(90.0, PoseGeometry.cameraPitchDegrees(s, 0f, 0f, s), 0.1)
    }

    @Test
    fun yawAloneDoesNotChangePitch() {
        // Turning on the spot must not read as raising or lowering the phone.
        val s = (sqrt(2.0) / 2).toFloat()
        assertEquals(0.0, PoseGeometry.cameraPitchDegrees(0f, s, 0f, s), 0.1)
    }

    @Test
    fun progressSmoothsThePitchRatherThanTrackingEveryWobble() {
        val s = (sqrt(2.0) / 2).toFloat()
        var progress = PoseTrackProgress.IDLE
        progress = progress.plus(pose(1, 0f, 0f, 0f, 1f))          // horizon
        assertEquals(0.0, assertNotNull(progress.pitchDegrees), 1e-6)
        progress = progress.plus(pose(2, -s, 0f, 0f, s))            // one floor-pointing sample
        val smoothed = assertNotNull(progress.pitchDegrees)
        // One outlier moves the readout a fifth of the way, not to −90.
        assertEquals(-90.0 * PoseTrackProgress.PITCH_SMOOTHING, smoothed, 1.0)
        assertTrue(smoothed > -30.0, "one wobble must not trip the coaching threshold")
    }

    private fun pose(t: Long, qx: Float, qy: Float, qz: Float, qw: Float) = PoseSample(
        monotonicNanos = t * 1_000_000_000,
        wallMillis = t,
        x = 0f, y = 0f, z = 0f,
        qx = qx, qy = qy, qz = qz, qw = qw,
        quality = TrackingQuality.NORMAL,
    )
}
