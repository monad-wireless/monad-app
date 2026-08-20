package sk.martinvanco.monad.lab.presentation

import sk.martinvanco.monad.lab.domain.LabInstrumentState
import sk.martinvanco.monad.lab.domain.Phase
import sk.martinvanco.monad.lab.domain.PoseSample
import sk.martinvanco.monad.lab.domain.PoseTrackProgress
import sk.martinvanco.monad.lab.domain.PoseTrackReport
import sk.martinvanco.monad.lab.domain.TrackingQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two operator-facing gates added after walk-fp-01 (2026-08-19): the live coaching sentence and
 * the stop warning. Both are pure derivations on [LabConsoleState], so the walks that motivated
 * them are encoded here as states and the sentences are pinned by tests rather than by afternoons.
 */
class WalkCoachingTest {

    private val report = PoseTrackReport(
        implementation = "arkit-world-tracking",
        commandedRateHz = 10.0,
        depthAssisted = true,
        worldAlignment = "gravity",
    )

    private fun running(
        progress: PoseTrackProgress,
        waypoints: List<WaypointRow> = emptyList(),
        elapsedSeconds: Long = 60,
    ) = LabConsoleState(
        instrument = LabInstrumentState.IDLE.copy(
            sessionId = "s",
            phase = Phase.RUNNING,
            startedMonotonicNanos = 1,
        ),
        nowMonotonicNanos = 1 + elapsedSeconds * 1_000_000_000,
        poseReport = report,
        poseProgress = progress,
        waypoints = waypoints,
        trackEnabled = true,
    )

    private fun progress(
        pitch: Double?,
        reason: String? = null,
        quality: TrackingQuality = if (reason == null) TrackingQuality.NORMAL else TrackingQuality.LIMITED,
        samples: Long = 100,
        normal: Long = 90,
        jumps: Long = 0,
    ) = PoseTrackProgress(
        samples = samples,
        pathLengthMetres = 10.0,
        last = PoseSample(
            monotonicNanos = 0, wallMillis = 0,
            x = 0f, y = 0f, z = 0f, qx = 0f, qy = 0f, qz = 0f, qw = 1f,
            quality = quality, reason = reason,
        ),
        normalSamples = normal,
        rejectedJumps = jumps,
        pitchDegrees = pitch,
    )

    // ---- coaching -----------------------------------------------------------------------

    @Test
    fun walkAWouldHaveBeenCoachedToRaiseThePhone() {
        // Walk A's actual numbers: median pitch −39°, most poses limited(initializing).
        val sentence = assertNotNull(running(progress(pitch = -39.0, reason = "initializing")).coaching)
        assertTrue(sentence.startsWith("RAISE THE PHONE"), sentence)
        // Pitch outranks the reason: raising the phone is the fix for both.
        assertTrue(sentence.contains("-39"), sentence)
    }

    @Test
    fun walkBWouldHaveWalkedUncoached() {
        // Walk B carried −14° and tracked normal: no sentence. Silence is what lets a sentence,
        // when it appears, be read.
        assertNull(running(progress(pitch = -14.0)).coaching)
    }

    @Test
    fun initializingGetsItsGraceBeforeItBecomesASentence() {
        val early = running(progress(pitch = -10.0, reason = "initializing"), elapsedSeconds = 5)
        assertNull(early.coaching)
        val late = running(progress(pitch = -10.0, reason = "initializing"), elapsedSeconds = 30)
        assertTrue(assertNotNull(late.coaching).contains("initialise"), late.coaching)
    }

    @Test
    fun repeatedJumpsAreCoachedEvenWhenTheLastSampleLooksNormal() {
        val state = running(progress(pitch = -10.0, samples = 100, jumps = 6))
        assertTrue(assertNotNull(state.coaching).contains("re-solving"), state.coaching)
    }

    @Test
    fun nothingIsCoachedWhenNothingTracks() {
        val state = running(progress(pitch = -80.0)).copy(poseReport = null)
        assertNull(state.coaching)
    }

    // ---- the stop gate ------------------------------------------------------------------

    @Test
    fun stoppingWithZeroWaypointsIsNamedBeforeItHappens() {
        // Both real walks closed with zero waypoints and nothing said so until analysis.
        val warning = assertNotNull(running(progress(pitch = -10.0)).stopWarningText)
        assertTrue(warning.contains("0 of 3 waypoints"), warning)
    }

    @Test
    fun anUntrustedTrackJoinsTheWarning() {
        val state = running(
            progress(pitch = -10.0, samples = 400, normal = 140),
            waypoints = listOf(
                WaypointRow("MONAD-FP-01", 1, 0f, 0f, TrackingQuality.NORMAL),
                WaypointRow("MONAD-FP-02", 2, 1f, 0f, TrackingQuality.NORMAL),
                WaypointRow("MONAD-FP-03", 3, 2f, 0f, TrackingQuality.NORMAL),
            ),
        )
        val warning = assertNotNull(state.stopWarningText)
        assertTrue(warning.contains("35 %"), warning)
        assertTrue(!warning.contains("waypoints are recorded"), "three waypoints satisfy the floor")
    }

    @Test
    fun aCleanWalkStopsWithoutCeremony() {
        val state = running(
            progress(pitch = -10.0, samples = 400, normal = 390),
            waypoints = List(3) { WaypointRow("MONAD-FP-0$it", it.toLong(), 0f, 0f, TrackingQuality.NORMAL) },
        )
        assertNull(state.stopWarningText)
    }

    @Test
    fun aWalkThatNeverTrackedStopsWithoutCeremony() {
        // A broadcast-only walk has no trajectory to protect; gating it would train the operator
        // to click through the dialog, which is how warnings die.
        assertEquals(null, running(progress(pitch = null)).copy(poseReport = null).stopWarningText)
    }
}
