package sk.martinvanco.monad.lab.presentation

import sk.martinvanco.monad.lab.domain.LabInstrumentState
import sk.martinvanco.monad.lab.domain.MeshProgress
import sk.martinvanco.monad.lab.domain.Phase
import sk.martinvanco.monad.lab.domain.PoseTrackReport
import sk.martinvanco.monad.lab.domain.WaypointAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two console derivations the 2026-08-26 survey walk paid for.
 *
 * **The anchor.** A walk's session frame is metric and internally consistent — that run measured
 * 99.91 % `normal` tracking over 235 m — and arbitrarily placed. Two typed site coordinates fix the
 * placement, three give it a residual. The failure mode being guarded is not a crash: it is a typed
 * anchor that does not parse being silently dropped, leaving the operator believing the transform is
 * pinned when it is not, and nobody discovering otherwise until the cards are in the register.
 *
 * **The mesh.** The same walk lost its 102.94 MB `mesh.ply` in the upload, and because `mesh.tsv`
 * went up cleanly every tool downstream read the absence as a device with no LiDAR. It was an iPhone
 * 17 Pro. The console is the one place that knows the difference while somebody is still in the room.
 */
class SurveyAnchorTest {

    // ---- the anchor -------------------------------------------------------------------------

    @Test
    fun aCommaSeparatedPairIsASiteCoordinate() {
        assertEquals(
            WaypointAnchor(x = 12.34, y = 5.67, source = WaypointAnchor.SOURCE_TAPE),
            LabConsoleState.parseAnchor("12.34, 5.67"),
        )
    }

    @Test
    fun spacesAndSemicolonsSeparateTooAndNegativesSurvive() {
        // The tape reading is whatever the operator types on a phone keyboard at arm's length, and a
        // site origin inside the building puts real cards at negative coordinates.
        assertEquals(WaypointAnchor(1.0, 2.0), LabConsoleState.parseAnchor("1 2"))
        assertEquals(WaypointAnchor(1.0, 2.0), LabConsoleState.parseAnchor(" 1 ; 2 "))
        assertEquals(WaypointAnchor(-3.5, 0.0), LabConsoleState.parseAnchor("-3.5, 0"))
    }

    @Test
    fun oneNumberIsNotACoordinate() {
        // Accepting "12.34" as (12.34, 0) would place the anchor on the origin line and pull the
        // whole fit toward it — a wrong answer that looks like a working one.
        assertNull(LabConsoleState.parseAnchor("12.34"))
        assertNull(LabConsoleState.parseAnchor("1 2 3"))
        assertNull(LabConsoleState.parseAnchor("here by the window"))
        assertNull(LabConsoleState.parseAnchor("12.34,"))
    }

    @Test
    fun aCommaDecimalSeparatorIsRefusedRatherThanGuessed() {
        // `12,34` and `12, 34` are the same string with two meanings, and the wrong reading is a
        // 22-metre error nothing downstream can detect.
        assertNull(LabConsoleState.parseAnchor("12,34,5,67"))
    }

    @Test
    fun anEmptyFieldIsNotAnError() {
        // Most cards are targets of the transform, not anchors for it. The empty state has to be the
        // quiet one or the operator learns to ignore the message.
        val state = LabConsoleState(surveyAnchor = "")
        assertNull(state.pendingAnchor)
        assertNull(state.anchorError)
    }

    @Test
    fun anUnparseableFieldReportsRatherThanDropping() {
        val state = LabConsoleState(surveyAnchor = "over there")
        assertNull(state.pendingAnchor)
        assertNotNull(state.anchorError)
        assertTrue(state.anchorError!!.contains("12.34"), state.anchorError!!)
    }

    @Test
    fun aValidFieldBecomesThePendingAnchor() {
        val state = LabConsoleState(surveyAnchor = "10.772, 11.879")
        assertEquals(WaypointAnchor(10.772, 11.879), state.pendingAnchor)
        assertNull(state.anchorError)
    }

    // ---- the mesh ---------------------------------------------------------------------------

    private fun walking(
        elapsedNanos: Long,
        diagnostics: List<String>,
        mesh: MeshProgress = MeshProgress.IDLE,
    ) = LabConsoleState(
        instrument = LabInstrumentState.IDLE.copy(
            phase = Phase.RUNNING,
            sessionId = "s-1",
            startedMonotonicNanos = 1,
        ),
        trackerDiagnostics = diagnostics,
        poseReport = PoseTrackReport(
            implementation = "arkit-world-tracking",
            commandedRateHz = 10.0,
            depthAssisted = true,
            worldAlignment = "gravity",
        ),
        mesh = mesh,
        nowMonotonicNanos = 1 + elapsedNanos,
    )

    @Test
    fun aPhoneWithLidarAndNoTrianglesIsCalledOut() {
        val state = walking(
            elapsedNanos = 60_000_000_000,
            diagnostics = listOf("LiDAR scene reconstruction: available"),
        )
        assertTrue(state.meshExpected)
        val warning = assertNotNull(state.meshWarning)
        assertTrue(warning.contains("NO ROOM GEOMETRY"), warning)
    }

    @Test
    fun aPhoneWithoutLidarIsNotNagged() {
        // "No LiDAR" is a fact about the device, not a defect. Warning about it would train the
        // operator to ignore the line that matters.
        val state = walking(
            elapsedNanos = 60_000_000_000,
            diagnostics = listOf("LiDAR scene reconstruction: absent"),
        )
        assertTrue(!state.meshExpected)
        assertNull(state.meshWarning)
    }

    @Test
    fun theFirstHalfMinuteIsGrace() {
        // Scene reconstruction needs a few seconds of motion before the first anchor appears, and a
        // walk that starts standing still would otherwise trip the warning immediately.
        val state = walking(
            elapsedNanos = 5_000_000_000,
            diagnostics = listOf("LiDAR scene reconstruction: available"),
        )
        assertNull(state.meshWarning)
    }

    @Test
    fun aBuildThatSaysNothingAboutDepthClaimsNothing() {
        // Null-shaped: an unknown platform must not be reported as a phone with LiDAR, because the
        // warning would then fire on every walk and mean nothing.
        val state = walking(elapsedNanos = 60_000_000_000, diagnostics = listOf("ARKit world tracking: supported"))
        assertTrue(!state.meshExpected)
        assertNull(state.meshWarning)
    }
}
