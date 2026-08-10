package sk.martinvanco.monad.lab.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The block state machine and its guard-rails.
 *
 * Every test here corresponds to an error that cannot be repaired after the session, because the
 * block label *is* the ground truth the analysis uses. The most important of them is
 * [aLevelThatDisagreesWithTheRoomTallyIsFlagged]: a block declared level 4 that actually ran at 6
 * is invisible in analysis, so the only place it can be caught is here, while people are still in
 * the room.
 */
class BlockControllerTest {

    private val sec = 1_000_000_000L

    private fun request(
        level: Int = 4,
        kind: BlockKind = BlockKind.STAIRCASE,
        zone: String = LabZones.A,
        sub: SubCondition = SubCondition.SEATED,
        id: String = "block-1",
    ) = BlockStartRequest(
        blockId = id,
        zoneId = zone,
        level = level,
        subCondition = sub,
        kind = kind,
    )

    private fun start(
        state: BlockSessionState = BlockSessionState.EMPTY,
        request: BlockStartRequest = request(),
        running: Boolean = true,
        mono: Long = 1_000 * sec,
        wall: Long = 1_700_000_000_000L,
        tally: BlockTally? = null,
        clock: ClockStamp? = null,
    ) = BlockMachine.start(state, request, running, mono, wall, tally, clock)

    // ---- refusals -------------------------------------------------------------------------

    @Test
    fun aBlockCannotBeStartedWithoutASession() {
        val result = start(running = false)
        assertTrue(result is BlockCommandResult.Rejected)
        assertEquals(BlockRejection.NO_SESSION, result.reason)
    }

    @Test
    fun aRefusedStartWritesNothing() {
        // The whole point of a refusal: a marker naming an interval of a stream that does not exist
        // is worse than no marker, because it looks like data.
        val result = start(running = false)
        assertTrue(result is BlockCommandResult.Rejected)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun aBlockWithNoZoneIsRefused() {
        val result = start(request = request(zone = ""))
        assertTrue(result is BlockCommandResult.Rejected)
        assertEquals(BlockRejection.NO_ZONE, result.reason)
    }

    @Test
    fun stoppingWithNothingRunningIsRefused() {
        val result = BlockMachine.stop(
            BlockSessionState.EMPTY,
            BlockStopReason.OPERATOR,
            0,
            0,
            null,
            null,
        )
        assertTrue(result is BlockCommandResult.Rejected)
        assertEquals(BlockRejection.NO_ACTIVE_BLOCK, result.reason)
    }

    // ---- the happy path -------------------------------------------------------------------

    @Test
    fun startThenStopProducesTwoEdgesAndOneCompletedBlock() {
        val opened = start() as BlockCommandResult.Applied
        assertEquals(1, opened.marks.size)
        assertEquals(BlockPhase.START, opened.marks.single().phase)
        assertEquals(SessionMarker.Kind.BLOCK_START, opened.marks.single().markerKind)
        assertEquals(1, opened.state.sequence)

        val closed = BlockMachine.stop(
            state = opened.state,
            reason = BlockStopReason.OPERATOR,
            monotonicNanos = 1_090 * sec,
            wallMillis = 1_700_000_090_000L,
            tally = null,
            clock = null,
        ) as BlockCommandResult.Applied

        val mark = closed.marks.single()
        assertEquals(BlockPhase.STOP, mark.phase)
        assertEquals(SessionMarker.Kind.BLOCK_STOP, mark.markerKind)
        assertEquals(90_000L, mark.durationMillis)
        // Both edges carry the same identity, which is what makes them one block.
        assertEquals(opened.marks.single().blockId, mark.blockId)
        assertNull(closed.state.active)
        assertEquals(90_000L, assertNotNull(closed.state.last).durationMillis)
    }

    @Test
    fun blockIdIsCarriedOnBothEdgesSoTheyCanBePaired() {
        val opened = start(request = request(id = "blk-stable")) as BlockCommandResult.Applied
        val closed = BlockMachine.stop(
            opened.state, BlockStopReason.OPERATOR, 1_100 * sec, 0, null, null,
        ) as BlockCommandResult.Applied
        assertEquals("blk-stable", opened.marks.single().blockId)
        assertEquals("blk-stable", closed.marks.single().blockId)
    }

    // ---- supersession ---------------------------------------------------------------------

    @Test
    fun startingWhileOneRunsAutoStopsThePreviousAndSaysSo() {
        // Refusing would lose the new block; nesting would make the marker stream ambiguous. The
        // previous condition really did end at this instant, and the analysis needs its trailing
        // edge as much as it needs the new leading one.
        val first = start(request = request(id = "a")) as BlockCommandResult.Applied
        val next = BlockMachine.start(
            state = first.state,
            request = request(id = "b", level = 0),
            sessionRunning = true,
            monotonicNanos = 1_100 * sec,
            wallMillis = 0,
            tally = null,
            clock = null,
        ) as BlockCommandResult.Applied

        assertEquals(2, next.marks.size, "an auto-stop then a start")
        assertEquals(BlockPhase.STOP, next.marks[0].phase)
        assertEquals("a", next.marks[0].blockId)
        assertEquals(BlockStopReason.SUPERSEDED, next.marks[0].stopReason)
        assertEquals(BlockPhase.START, next.marks[1].phase)
        assertEquals("b", next.marks[1].blockId)
        assertEquals(2, next.state.sequence)
        assertTrue(
            next.marks[0].warnings.any { it.kind == BlockWarningKind.NOT_CLOSED_BY_OPERATOR },
            "the trailing edge is an artefact of the next block starting, and says so",
        )
    }

    @Test
    fun aSessionEndClosesAnOpenBlockAndRecordsThatItWasAutomatic() {
        val opened = start() as BlockCommandResult.Applied
        val closed = BlockMachine.stop(
            state = opened.state,
            reason = BlockStopReason.SESSION_END,
            monotonicNanos = 1_090 * sec,
            wallMillis = 0,
            tally = null,
            clock = null,
        ) as BlockCommandResult.Applied

        assertEquals(BlockStopReason.SESSION_END, closed.marks.single().stopReason)
        assertTrue(
            closed.marks.single().warnings.any {
                it.kind == BlockWarningKind.NOT_CLOSED_BY_OPERATOR
            }
        )
    }

    // ---- guard-rails ----------------------------------------------------------------------

    @Test
    fun aLevelThatDisagreesWithTheRoomTallyIsFlagged() {
        val result = start(
            request = request(level = 4),
            tally = BlockTally(6, TallySource.ROOM_LIVE),
        ) as BlockCommandResult.Applied

        val warning = result.warnings.firstOrNull {
            it.kind == BlockWarningKind.LEVEL_DISAGREES_WITH_TALLY
        }
        assertNotNull(warning, "a mislabelled block is unrecoverable; this is the only guard")
        assertTrue(warning.message.contains("4"))
        assertTrue(warning.message.contains("6"))
    }

    @Test
    fun aLevelThatMatchesTheRoomTallyIsNotFlagged() {
        val result = start(
            request = request(level = 4),
            tally = BlockTally(4, TallySource.ROOM_LIVE),
        ) as BlockCommandResult.Applied
        assertTrue(result.warnings.none { it.kind == BlockWarningKind.LEVEL_DISAGREES_WITH_TALLY })
        assertTrue(result.warnings.none { it.kind == BlockWarningKind.TALLY_NOT_ROOM_WIDE })
    }

    @Test
    fun aDeviceOnlyTallyIsNotTreatedAsADisagreement() {
        // This handset sees one participant out of twelve. Comparing that to a declared level would
        // fire on every block, and an alarm that always fires is an alarm nobody reads.
        val result = start(
            request = request(level = 4),
            tally = BlockTally(1, TallySource.DEVICE_ONLY),
        ) as BlockCommandResult.Applied
        assertTrue(result.warnings.none { it.kind == BlockWarningKind.LEVEL_DISAGREES_WITH_TALLY })
        assertTrue(result.warnings.any { it.kind == BlockWarningKind.TALLY_NOT_ROOM_WIDE })
    }

    @Test
    fun aStaleRoomTallyStillChecksTheLevelButSaysItIsWeakEvidence() {
        val result = start(
            request = request(level = 4),
            tally = BlockTally(6, TallySource.ROOM_STALE),
        ) as BlockCommandResult.Applied
        assertTrue(result.warnings.any { it.kind == BlockWarningKind.LEVEL_DISAGREES_WITH_TALLY })
        assertTrue(result.warnings.any { it.kind == BlockWarningKind.TALLY_STALE })
    }

    @Test
    fun aSetupBlockCarriesNoOccupancyClaimAndIsNotComparedToTheTally() {
        val result = start(
            request = request(level = 0, kind = BlockKind.SETUP),
            tally = BlockTally(7, TallySource.ROOM_LIVE),
        ) as BlockCommandResult.Applied
        assertTrue(result.warnings.none { it.kind == BlockWarningKind.LEVEL_DISAGREES_WITH_TALLY })
    }

    @Test
    fun aLevelOffTheFrozenStaircaseIsFlagged() {
        val result = start(request = request(level = 5)) as BlockCommandResult.Applied
        assertTrue(result.warnings.any { it.kind == BlockWarningKind.LEVEL_OFF_STAIRCASE })
        // 5 is a perfectly ordinary number and not on the frozen staircase.
        assertFalse(LabStaircase.isRegistered(5))
        assertTrue(LabStaircase.isRegistered(6))
    }

    @Test
    fun aCyclingBlockAwayFromTheZeroFourPatternIsFlagged() {
        val onPattern = start(
            request = request(level = 4, kind = BlockKind.CYCLING_PLATEAU),
        ) as BlockCommandResult.Applied
        assertTrue(onPattern.warnings.none { it.kind == BlockWarningKind.LEVEL_OFF_PATTERN })

        val offPattern = start(
            request = request(level = 8, kind = BlockKind.CYCLING_PLATEAU),
        ) as BlockCommandResult.Applied
        assertTrue(offPattern.warnings.any { it.kind == BlockWarningKind.LEVEL_OFF_PATTERN })
    }

    @Test
    fun anEmptyRoomBlockMustBeEmpty() {
        val result = start(
            request = request(level = 4, kind = BlockKind.EMPTY_ROOM),
        ) as BlockCommandResult.Applied
        assertTrue(result.warnings.any { it.kind == BlockWarningKind.LEVEL_OFF_PATTERN })
    }

    // ---- duration budgets -----------------------------------------------------------------

    @Test
    fun aPlateauThatRanTooLongIsFlaggedAtTheStopEdge() {
        // 1.5 min plateau; four minutes means it has swallowed a ramp, and the analysis — which
        // trusts the label — would then average a transition into a steady state.
        val opened = start(
            request = request(level = 4, kind = BlockKind.CYCLING_PLATEAU),
        ) as BlockCommandResult.Applied
        val closed = BlockMachine.stop(
            opened.state, BlockStopReason.OPERATOR, 1_240 * sec, 0, null, null,
        ) as BlockCommandResult.Applied

        assertTrue(closed.warnings.any { it.kind == BlockWarningKind.DURATION_OVER_BUDGET })
        assertFalse(assertNotNull(closed.state.last).withinBudget)
    }

    @Test
    fun aPlateauCutShortIsFlaggedToo() {
        val opened = start(
            request = request(level = 0, kind = BlockKind.CYCLING_PLATEAU),
        ) as BlockCommandResult.Applied
        val closed = BlockMachine.stop(
            opened.state, BlockStopReason.OPERATOR, 1_020 * sec, 0, null, null,
        ) as BlockCommandResult.Applied
        assertTrue(closed.warnings.any { it.kind == BlockWarningKind.DURATION_UNDER_BUDGET })
    }

    @Test
    fun aPlateauInsideItsBudgetIsClean() {
        val opened = start(
            request = request(level = 4, kind = BlockKind.CYCLING_PLATEAU),
            tally = BlockTally(4, TallySource.ROOM_LIVE),
        ) as BlockCommandResult.Applied
        val closed = BlockMachine.stop(
            opened.state,
            BlockStopReason.OPERATOR,
            1_090 * sec,
            0,
            BlockTally(4, TallySource.ROOM_LIVE),
            null,
        ) as BlockCommandResult.Applied
        assertTrue(closed.warnings.isEmpty(), "was ${closed.warnings.map { it.kind }}")
        assertTrue(assertNotNull(closed.state.last).withinBudget)
    }

    @Test
    fun bothRegisteredStaircaseSubBlockBudgetsAreInsideTheBand() {
        // 175 s (full staircase) and 350 s (reduced staircase) are both registered in §2, and the
        // console does not know which day it is — so neither may warn.
        listOf(175, 350).forEach { seconds ->
            assertTrue(
                BlockKind.STAIRCASE.isWithinBudget(seconds * 1000L),
                "the pre-registered ${seconds} s sub-block must not warn",
            )
        }
        assertFalse(BlockKind.STAIRCASE.isWithinBudget(30_000L))
        assertFalse(BlockKind.STAIRCASE.isWithinBudget(900_000L))
    }

    @Test
    fun rampAndPlateauBudgetsMatchTheFrozenCyclingDesign() {
        assertEquals(30, BlockKind.CYCLING_RAMP.designedSeconds, "0.5 min ramp")
        assertEquals(90, BlockKind.CYCLING_PLATEAU.designedSeconds, "1.5 min plateau")
        assertTrue(BlockKind.CYCLING_RAMP.isWithinBudget(30_000))
        assertTrue(BlockKind.CYCLING_PLATEAU.isWithinBudget(90_000))
    }

    @Test
    fun anUntimedBlockNeverComplainsAboutItsDuration() {
        val opened = start(
            request = request(level = 0, kind = BlockKind.SETUP),
        ) as BlockCommandResult.Applied
        val closed = BlockMachine.stop(
            opened.state, BlockStopReason.OPERATOR, 5_000 * sec, 0, null, null,
        ) as BlockCommandResult.Applied
        assertTrue(closed.warnings.none { it.kind == BlockWarningKind.DURATION_OVER_BUDGET })
        assertTrue(closed.warnings.none { it.kind == BlockWarningKind.DURATION_UNDER_BUDGET })
    }

    // ---- live over-budget -----------------------------------------------------------------

    @Test
    fun aRunningBlockRaisesOverBudgetBeforeItIsStopped() {
        val opened = start(
            request = request(level = 4, kind = BlockKind.CYCLING_PLATEAU),
        ) as BlockCommandResult.Applied
        val active = assertNotNull(opened.state.active)

        assertTrue(BlockGuards.whileRunning(active, 1_090 * sec).isEmpty(), "inside the budget")
        val late = BlockGuards.whileRunning(active, 1_200 * sec)
        assertEquals(1, late.size)
        assertEquals(BlockWarningKind.RUNNING_OVER_BUDGET, late.single().kind)
    }

    // ---- clock precision ------------------------------------------------------------------

    @Test
    fun aBoundaryOutsideTheT3BudgetIsFlaggedAtTheEdge() {
        // Cycling ramps are 30 s and are T3's primary event set, so a boundary is held to G4b's
        // 250 ms — not to G4a's 6 s, which would be a fifth of a ramp.
        val stamp = ClockStamp.NONE.copy(
            status = ClockGateStatus.OK,
            samples = 4,
            fitResidualMillis = 900.0,
            meetsAllTestsBudget = true,
            meetsT3Budget = false,
        )
        val result = start(clock = stamp) as BlockCommandResult.Applied
        assertTrue(result.warnings.any { it.kind == BlockWarningKind.CLOCK_MISSES_T3_BUDGET })
        assertTrue(result.warnings.none { it.kind == BlockWarningKind.CLOCK_MISSES_ALL_TESTS_BUDGET })
    }

    @Test
    fun aBoundaryThatCannotBePlacedAtAllIsFlaggedMoreLoudly() {
        val stamp = ClockStamp.NONE.copy(
            status = ClockGateStatus.NO_SAMPLES,
            meetsAllTestsBudget = false,
            meetsT3Budget = false,
        )
        val result = start(clock = stamp) as BlockCommandResult.Applied
        assertTrue(result.warnings.any { it.kind == BlockWarningKind.CLOCK_MISSES_ALL_TESTS_BUDGET })
    }

    @Test
    fun aWellSyncedBoundaryRaisesNoClockWarning() {
        val stamp = ClockStamp.NONE.copy(
            status = ClockGateStatus.OK,
            samples = 5,
            fitResidualMillis = 12.0,
            meetsAllTestsBudget = true,
            meetsT3Budget = true,
        )
        val result = start(clock = stamp) as BlockCommandResult.Applied
        assertTrue(
            result.warnings.none {
                it.kind == BlockWarningKind.CLOCK_MISSES_T3_BUDGET ||
                    it.kind == BlockWarningKind.CLOCK_MISSES_ALL_TESTS_BUDGET
            }
        )
    }
}
