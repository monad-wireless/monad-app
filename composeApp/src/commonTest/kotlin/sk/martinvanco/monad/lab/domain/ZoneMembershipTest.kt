package sk.martinvanco.monad.lab.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Three zones in one hall, and a participant who walks between them.
 *
 * The occupancy of each zone is derived as a cumulative sum of `direction` over
 * `(lab_session_id, zone_id)`. Every case here is a way that sum could quietly go wrong.
 */
class ZoneMembershipTest {

    private var nonce = 0

    private fun event(
        zone: String,
        direction: GroundTruthDirection,
        monoNanos: Long,
        wallMillis: Long = monoNanos / 1_000_000,
        session: String = "lab-1",
    ) = GroundTruthEvent(
        labSessionId = session,
        participantToken = "p1",
        zoneId = zone,
        direction = direction,
        site = "fiit-library",
        monotonicNanos = monoNanos,
        wallMillis = wallMillis,
        scanNonce = "n${nonce++}",
        recordingSessionId = null,
    )

    // ---- membership ------------------------------------------------------------------------

    @Test
    fun noScansMeansNotCheckedIn() {
        val state = ZoneMembership.resolve(emptyList())
        assertNull(state.current)
        assertEquals("not checked in", state.zoneLabel)
    }

    @Test
    fun anEntryFollowedByAnExitLeavesTheZone() {
        val state = ZoneMembership.resolve(
            listOf(
                event("zone-a", GroundTruthDirection.IN, 1_000_000_000),
                event("zone-a", GroundTruthDirection.OUT, 2_000_000_000),
            )
        )
        assertNull(state.current)
    }

    @Test
    fun orderingIsByMonotonicNanosNotByArrayOrder() {
        // wall_ms is subject to NTP steps and user edits mid-session; re-ordering two scans on it
        // would flip a participant's zone. The pre-registration names mono_ns as the join key.
        val state = ZoneMembership.resolve(
            listOf(
                event("zone-a", GroundTruthDirection.OUT, 5_000_000_000, wallMillis = 1),
                event("zone-a", GroundTruthDirection.IN, 1_000_000_000, wallMillis = 9_999),
            )
        )
        assertNull(state.current, "the OUT is later on the monotonic clock and must win")
    }

    @Test
    fun theMostRecentEntryIsTheCurrentZoneAndOthersAreFlagged() {
        // History from before implicit exits existed: two zones left open.
        val state = ZoneMembership.resolve(
            listOf(
                event("zone-a", GroundTruthDirection.IN, 1_000_000_000),
                event("zone-b", GroundTruthDirection.IN, 2_000_000_000),
            )
        )
        assertEquals("zone-b", state.current?.zoneId)
        assertTrue(state.isAmbiguous)
        assertEquals(listOf("zone-a"), state.ambiguous.map { it.zoneId })
    }

    // ---- planning --------------------------------------------------------------------------

    @Test
    fun theFirstScanOfAToggleCodeIsAnEntry() {
        val decision = assertIs<ScanDecision.Record>(
            ScanPlanner.plan(GroundTruthTicket("lab-1", "zone-a"), emptyList(), nowWallMillis = 1_000)
        )
        assertEquals(GroundTruthDirection.IN, decision.primary.direction)
        assertEquals(1, decision.rows.size)
    }

    @Test
    fun aSecondScanOfAToggleCodeIsAnExit() {
        val history = listOf(event("zone-a", GroundTruthDirection.IN, 1_000_000_000, wallMillis = 1_000))
        val decision = assertIs<ScanDecision.Record>(
            ScanPlanner.plan(GroundTruthTicket("lab-1", "zone-a"), history, nowWallMillis = 60_000)
        )
        assertEquals(GroundTruthDirection.OUT, decision.primary.direction)
        assertNull(decision.movedFrom)
    }

    @Test
    fun anExplicitDirectionIgnoresHistoryEntirely() {
        val history = listOf(event("zone-a", GroundTruthDirection.IN, 1_000_000_000, wallMillis = 1_000))
        val decision = assertIs<ScanDecision.Record>(
            ScanPlanner.plan(
                GroundTruthTicket("lab-1", "zone-a", declaredDirection = GroundTruthDirection.IN),
                history,
                nowWallMillis = 60_000,
            )
        )
        assertEquals(GroundTruthDirection.IN, decision.primary.direction)
    }

    @Test
    fun enteringAZoneLeavesThePreviousOne() {
        // A participant who walks A → B and scans only B's code would otherwise leave A's count one
        // person too high for the rest of the session, invisibly.
        val history = listOf(event("zone-a", GroundTruthDirection.IN, 1_000_000_000, wallMillis = 1_000))
        val decision = assertIs<ScanDecision.Record>(
            ScanPlanner.plan(GroundTruthTicket("lab-1", "zone-b"), history, nowWallMillis = 60_000)
        )

        assertEquals(2, decision.rows.size)
        assertEquals(PlannedScan("zone-a", GroundTruthDirection.OUT, implied = true), decision.rows[0])
        assertEquals(PlannedScan("zone-b", GroundTruthDirection.IN), decision.rows[1])
        assertEquals("zone-a", decision.movedFrom)
    }

    @Test
    fun everyOpenZoneIsClosedWhenEnteringAnother() {
        val history = listOf(
            event("zone-a", GroundTruthDirection.IN, 1_000_000_000, wallMillis = 1_000),
            event("zone-b", GroundTruthDirection.IN, 2_000_000_000, wallMillis = 2_000),
        )
        val decision = assertIs<ScanDecision.Record>(
            ScanPlanner.plan(GroundTruthTicket("lab-1", "zone-c"), history, nowWallMillis = 60_000)
        )
        val exits = decision.rows.filter { it.implied }.map { it.zoneId }.toSet()
        assertEquals(setOf("zone-a", "zone-b"), exits)
        assertEquals("zone-c", decision.primary.zoneId)
    }

    @Test
    fun leavingAZoneWritesNoImplicitExits() {
        val history = listOf(event("zone-a", GroundTruthDirection.IN, 1_000_000_000, wallMillis = 1_000))
        val decision = assertIs<ScanDecision.Record>(
            ScanPlanner.plan(
                GroundTruthTicket("lab-1", "zone-a", declaredDirection = GroundTruthDirection.OUT),
                history,
                nowWallMillis = 60_000,
            )
        )
        assertEquals(1, decision.rows.size)
        assertNull(decision.movedFrom)
    }

    @Test
    fun aDoubleScanInsideTheWindowRecordsNothing() {
        // Somebody unsure whether it worked, tapping again in a doorway. Not an error, and not a
        // second person.
        val history = listOf(event("zone-a", GroundTruthDirection.IN, 1_000_000_000, wallMillis = 10_000))
        val decision = assertIs<ScanDecision.Duplicate>(
            ScanPlanner.plan(GroundTruthTicket("lab-1", "zone-a"), history, nowWallMillis = 13_000)
        )
        assertEquals(3_000L, decision.ageMillis)
    }

    @Test
    fun theSameCodeAfterTheWindowIsARealToggle() {
        val history = listOf(event("zone-a", GroundTruthDirection.IN, 1_000_000_000, wallMillis = 10_000))
        val decision = assertIs<ScanDecision.Record>(
            ScanPlanner.plan(GroundTruthTicket("lab-1", "zone-a"), history, nowWallMillis = 30_000)
        )
        assertEquals(GroundTruthDirection.OUT, decision.primary.direction)
    }

    @Test
    fun aDifferentZoneIsNeverADuplicateOfThisOne() {
        val history = listOf(event("zone-a", GroundTruthDirection.IN, 1_000_000_000, wallMillis = 10_000))
        assertIs<ScanDecision.Record>(
            ScanPlanner.plan(GroundTruthTicket("lab-1", "zone-b"), history, nowWallMillis = 11_000)
        )
    }

    @Test
    fun aCodeForAnotherLabSessionIsFlaggedRatherThanSilentlyAccepted() {
        val decision = assertIs<ScanDecision.Record>(
            ScanPlanner.plan(
                GroundTruthTicket("lab-2", "zone-a"),
                history = emptyList(),
                nowWallMillis = 1_000,
                knownSessions = setOf("lab-1"),
            )
        )
        assertEquals("lab-1", decision.sessionChangedFrom)
    }

    @Test
    fun stayingInTheSameLabSessionRaisesNoSessionChange() {
        val decision = assertIs<ScanDecision.Record>(
            ScanPlanner.plan(
                GroundTruthTicket("lab-1", "zone-a"),
                history = emptyList(),
                nowWallMillis = 1_000,
                knownSessions = setOf("lab-1"),
            )
        )
        assertNull(decision.sessionChangedFrom)
    }

    @Test
    fun aCodeWithoutASessionOrZoneIsRejected() {
        assertIs<ScanDecision.Rejected>(
            ScanPlanner.plan(GroundTruthTicket("", "zone-a"), emptyList(), 1_000)
        )
        assertIs<ScanDecision.Rejected>(
            ScanPlanner.plan(GroundTruthTicket("lab-1", ""), emptyList(), 1_000)
        )
    }

    @Test
    fun aFullThreeZoneTraversalLeavesEveryCountBalanced() {
        // A → B → C → out. Every zone must end at net zero, which is the property the occupancy
        // sum depends on.
        val recorded = mutableListOf<GroundTruthEvent>()
        var mono = 1_000_000_000L
        var wall = 10_000L

        fun scan(zone: String, declared: GroundTruthDirection? = null) {
            val decision = ScanPlanner.plan(
                GroundTruthTicket("lab-1", zone, declaredDirection = declared),
                recorded.toList(),
                nowWallMillis = wall,
            )
            assertIs<ScanDecision.Record>(decision).rows.forEach {
                recorded += event(it.zoneId, it.direction, mono, wall)
            }
            mono += 60_000_000_000L
            wall += 60_000L
        }

        scan("zone-a")
        scan("zone-b")
        scan("zone-c")
        scan("zone-c", declared = GroundTruthDirection.OUT)

        val net = recorded.groupBy { it.zoneId }.mapValues { (_, rows) ->
            rows.sumOf { if (it.direction == GroundTruthDirection.IN) 1 else -1 }
        }
        assertEquals(mapOf("zone-a" to 0, "zone-b" to 0, "zone-c" to 0), net)
        assertNull(ZoneMembership.resolve(recorded).current)
    }
}
