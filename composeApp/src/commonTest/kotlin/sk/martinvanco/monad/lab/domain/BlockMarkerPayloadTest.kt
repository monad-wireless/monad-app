package sk.martinvanco.monad.lab.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The marker field contract.
 *
 * These names are what the analysis joins on, so renaming one is a **silent corpus split** rather
 * than a compile error. Asserting the literal strings is the only thing that turns that back into a
 * failing test.
 */
class BlockMarkerPayloadTest {

    private val stamp = ClockStamp(
        status = ClockGateStatus.OK,
        samples = 5,
        offsetNanos = -1_234_567,
        rttMillis = 4.5,
        skewPpm = 3.25,
        fitResidualMillis = 0.83,
        syncAgeMillis = 120,
        estimatedReferenceNanos = 1_700_000_000_000_000_000L,
        estimateSource = ClockEstimateSource.AFFINE_FIT,
        samplesRejected = 1,
        meetsAllTestsBudget = true,
        meetsT3Budget = true,
    )

    private fun mark(
        phase: BlockPhase = BlockPhase.START,
        duration: Long? = null,
        reason: BlockStopReason? = null,
    ) = BlockMark(
        phase = phase,
        blockId = "blk-7",
        zoneId = LabZones.B,
        level = 4,
        subCondition = SubCondition.WALKING,
        kind = BlockKind.CYCLING_PLATEAU,
        sequence = 12,
        monotonicNanos = 987_654_321_000L,
        wallMillis = 1_700_000_000_000L,
        durationMillis = duration,
        stopReason = reason,
        tally = BlockTally(4, TallySource.ROOM_LIVE),
        clock = stamp,
        warnings = listOf(
            BlockWarning(BlockWarningKind.TALLY_STALE, "stale"),
        ),
    )

    @Test
    fun everyContractFieldIsPresentUnderItsRegisteredName() {
        val json = BlockMarkerPayload.encode(
            BlockMarkerPayload.of(mark(), labSessionId = "lab-1", recordingSessionId = "lab-1")
        )
        listOf(
            "\"block_id\"", "\"zone_id\"", "\"level\"", "\"sub_condition\"", "\"block_kind\"",
            "\"phase\"", "\"lab_session_id\"", "\"recording_session_id\"", "\"sequence\"",
            "\"designed_seconds\"", "\"budget_min_seconds\"", "\"budget_max_seconds\"",
            "\"tally\"", "\"tally_source\"", "\"warnings\"", "\"clock\"",
        ).forEach { field ->
            assertTrue(json.contains(field), "the contract lost $field:\n$json")
        }
    }

    @Test
    fun theClockBlockCarriesThePrecisionOfThisBoundary() {
        val json = BlockMarkerPayload.encode(
            BlockMarkerPayload.of(mark(), "lab-1", "lab-1")
        )
        listOf(
            "\"fit_residual_ms\"", "\"sync_age_ms\"", "\"meets_g4a\"", "\"meets_g4b\"",
            "\"est_ref_ns\"", "\"est_ref_source\"", "\"samples_rejected\"", "\"rtt_ms\"",
        ).forEach { field ->
            assertTrue(json.contains(field), "the clock contract lost $field:\n$json")
        }
    }

    @Test
    fun aStopEdgeAddsDurationReasonAndBudgetVerdict() {
        val payload = BlockMarkerPayload.of(
            mark(BlockPhase.STOP, duration = 240_000, reason = BlockStopReason.SESSION_END),
            "lab-1",
            "lab-1",
        )
        assertEquals("stop", payload.phase)
        assertEquals(240_000L, payload.durationMillis)
        assertEquals("session_end", payload.stopReason)
        // A 1.5 min plateau that ran four minutes is out of budget, and the marker says so rather
        // than leaving the analysis to recompute it from constants it does not have.
        assertEquals(false, payload.withinBudget)
    }

    @Test
    fun aStartEdgeCarriesNoDurationOrVerdict() {
        val payload = BlockMarkerPayload.of(mark(), "lab-1", "lab-1")
        assertEquals("start", payload.phase)
        assertNull(payload.durationMillis)
        assertNull(payload.stopReason)
        assertNull(payload.withinBudget)
    }

    @Test
    fun theWireVocabularyIsStable() {
        val payload = BlockMarkerPayload.of(mark(), "lab-1", "lab-1")
        assertEquals("walking", payload.subCondition)
        assertEquals("cycling_plateau", payload.blockKind)
        assertEquals("room_live", payload.tallySource)
        assertEquals(listOf("tally_stale"), payload.warnings)
        assertEquals(90, payload.designedSeconds)
        assertEquals(72, payload.budgetMinSeconds)
        assertEquals(108, payload.budgetMaxSeconds)
    }

    @Test
    fun itRoundTrips() {
        val original = BlockMarkerPayload.of(
            mark(BlockPhase.STOP, 90_000, BlockStopReason.OPERATOR),
            "lab-1",
            "rec-1",
        )
        val decoded = assertNotNull(BlockMarkerPayload.decode(BlockMarkerPayload.encode(original)))
        assertEquals(original, decoded)
    }

    @Test
    fun markerKindsUseTheRegisteredWireNames() {
        assertEquals("block_start", SessionMarker.Kind.BLOCK_START.wire)
        assertEquals("block_stop", SessionMarker.Kind.BLOCK_STOP.wire)
        assertEquals(SessionMarker.Kind.BLOCK_START, SessionMarker.Kind.fromWire("block_start"))
        assertEquals(SessionMarker.Kind.BLOCK_STOP, SessionMarker.Kind.fromWire("block_stop"))
        assertTrue(SessionMarker.Kind.BLOCK_START.isBlockEdge)
        assertTrue(!SessionMarker.Kind.CLOCK_SYNC.isBlockEdge)
    }

    @Test
    fun anUnknownWireKindStillDegradesToAnnotationRatherThanThrowing() {
        // A build reading a stream written by a newer one must not crash on it.
        assertEquals(SessionMarker.Kind.ANNOTATION, SessionMarker.Kind.fromWire("block_pause"))
    }

    @Test
    fun theLabelIsReadableOnABench() {
        assertEquals("ZONE-B L4 walking cycling_plateau start", mark().label)
        assertTrue(
            mark(BlockPhase.STOP, 90_000, BlockStopReason.OPERATOR).label.endsWith("(1m 30s)")
        )
    }
}
