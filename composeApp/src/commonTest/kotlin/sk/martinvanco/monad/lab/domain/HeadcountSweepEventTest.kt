package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v3 event field contract and the replay rules (IP-162).
 *
 * The names are what the backend and the analysis join on, so a rename is a silent corpus split
 * rather than a compile error; asserting the literal strings is what turns that back into a
 * failing test. The replay cases mirror the shared fixtures' expected projections; the complete
 * fixture set runs against the copied bytes in `androidUnitTest`.
 */
class HeadcountSweepEventTest {

    private val recording = "0f000000-0000-4000-8000-00000000000a"
    private val enrollment = "0e000000-0000-4000-8000-00000000000b"
    private val step = "0c000000-0000-4000-8000-00000000000c"
    private val sweep = "5e000000-0000-4000-8000-000000000001"
    private val protocol = "b33e25d256344f01b2991c38afda5dc1592ba801a6cb6e17fac120f3ebc1e24a"

    private fun eid(n: Int) = "e0000000-0000-4000-8000-${n.toString().padStart(12, '0')}"

    private fun event(
        sequence: Int,
        type: String,
        count: Int? = null,
        onAir: Boolean? = true,
        supersedes: String? = null,
        reason: String? = null,
        coverage: String? = null,
        coverageReason: String? = null,
        observers: Int? = 1,
        sweepId: String = sweep,
    ) = HeadcountSweepEvent(
        eventId = eid(sequence),
        sweepId = sweepId,
        sequence = sequence,
        eventType = type,
        recordingSessionId = recording,
        enrollmentId = enrollment,
        stepCompletionId = step,
        protocolId = "fixture-room-sweep",
        protocolSha256 = protocol,
        roomId = "fixture-room-a",
        coverageVersion = "c1",
        onAir = onAir,
        prompt = "People counted so far",
        count = count,
        supersedesEventId = supersedes,
        reason = reason,
        coverage = coverage,
        coverageReason = coverageReason,
        occupancyStability = if (type == HeadcountSweepEvent.FINALISE) "stable" else null,
        activity = if (type == HeadcountSweepEvent.FINALISE) "seated" else null,
        duplicateRisk = if (type == HeadcountSweepEvent.FINALISE) "none_observed" else null,
        // Only start and finalise carry the observer count; on any other type the schema forbids
        // it, and toJson() would rightly drop it — which a round-trip test must not mistake for a bug.
        observersInArea = if (type == HeadcountSweepEvent.START || type == HeadcountSweepEvent.FINALISE) observers else null,
    )

    private fun stream(vararg events: HeadcountSweepEvent) =
        events.mapIndexed { index, e -> (100_000_000_000L + index * 30_000_000_000L) to e }

    @Test
    fun everyContractFieldIsPresentUnderItsRegisteredName() {
        val json = event(2, HeadcountSweepEvent.CHECKPOINT, count = 3).toJson()
        for (name in listOf(
            "schema", "event_id", "sweep_id", "sequence", "event_type", "recording_session_id", "enrollment_id",
            "step_completion_id", "protocol_id", "protocol_sha256", "mode", "room_id", "coverage_version",
            "on_air", "prompt", "count",
        )) {
            assertTrue(name in json, "missing $name")
        }
        assertEquals("monad-app/headcount-marker/v3", json["schema"].toString().trim('"'))
        assertEquals("room_sweep_cumulative", json["mode"].toString().trim('"'))
    }

    @Test
    fun aStartCarriesNoCountAndAPresentNullableObserverCount() {
        val json = event(1, HeadcountSweepEvent.START, observers = null).toJson()
        assertFalse("count" in json, "a start event has no count; a reader must never fall back to the label")
        assertTrue("observers_in_area" in json)
        assertEquals(JsonNull, json["observers_in_area"])
        assertEquals("true", json["observer_excluded"].toString())
    }

    @Test
    fun onAirNullIsEncodedAsNullNotOmitted() {
        val json = event(2, HeadcountSweepEvent.CHECKPOINT, count = 1, onAir = null).toJson()
        assertEquals(JsonNull, json["on_air"])
    }

    @Test
    fun encodeIsCanonicalAndRoundTrips() {
        val original = event(2, HeadcountSweepEvent.CHECKPOINT, count = 3)
        val text = original.encode()
        assertEquals(text, CanonicalJson.encode(Json.parseToJsonElement(text) as JsonObject))
        val parsed = assertNotNull(HeadcountSweepEvent.parse(text))
        assertEquals(original, parsed)
        assertEquals(original.digest(), parsed.digest())
    }

    @Test
    fun legacyPayloadsAreNotSweepEvents() {
        assertNull(HeadcountSweepEvent.parse("""{"schema":"monad-app/headcount-marker/v2","count":4,"reading":1,"of_readings":5,"prompt":"x","on_air":true}"""))
        assertNull(HeadcountSweepEvent.parse("""{"count":4,"reading":1}"""))
        assertNull(HeadcountSweepEvent.parse(""))
    }

    @Test
    fun aCompleteSweepFinalises() {
        val state = HeadcountSweepReplay.replay(
            stream(
                event(1, HeadcountSweepEvent.START),
                event(2, HeadcountSweepEvent.CHECKPOINT, count = 3),
                event(3, HeadcountSweepEvent.CHECKPOINT, count = 5),
                event(4, HeadcountSweepEvent.CHECKPOINT, count = 5),
                event(5, HeadcountSweepEvent.FINALISE, count = 6, coverage = "complete"),
            ),
        )
        assertEquals(SweepPhase.FINALISED, state.phase)
        assertTrue(state.isValid, state.errors.toString())
        assertEquals(6, state.finalCount)
        assertEquals(listOf(3, 5, 5), state.checkpoints.map { it.count })
        assertEquals(6, state.nextSequence)
        assertEquals(100_000_000_000L, state.startMonotonicNanos)
        assertEquals(220_000_000_000L, state.endMonotonicNanos)
    }

    @Test
    fun anIdenticalRetryIsOneEventAndAConflictIsNeither() {
        val cp = event(2, HeadcountSweepEvent.CHECKPOINT, count = 3)
        val retry = HeadcountSweepReplay.replay(stream(event(1, HeadcountSweepEvent.START), cp, cp))
        assertEquals(1, retry.duplicatesIgnored)
        assertEquals(1, retry.checkpoints.size)
        assertTrue(retry.isValid)

        val conflict = HeadcountSweepReplay.replay(
            stream(event(1, HeadcountSweepEvent.START), cp, event(2, HeadcountSweepEvent.CHECKPOINT, count = 4)),
        )
        assertEquals(listOf("sequence_conflict"), conflict.errors.map { it.code })
        assertEquals(listOf(3), conflict.checkpoints.map { it.count })
    }

    @Test
    fun aDecreaseNeedsACorrectionWithAReason() {
        val plain = HeadcountSweepReplay.replay(
            stream(
                event(1, HeadcountSweepEvent.START),
                event(2, HeadcountSweepEvent.CHECKPOINT, count = 5),
                event(3, HeadcountSweepEvent.CHECKPOINT, count = 4),
            ),
        )
        assertEquals(listOf("count_decreased_without_correction"), plain.errors.map { it.code })

        val corrected = HeadcountSweepReplay.replay(
            stream(
                event(1, HeadcountSweepEvent.START),
                event(2, HeadcountSweepEvent.CHECKPOINT, count = 4),
                event(3, HeadcountSweepEvent.CHECKPOINT, count = 7),
                event(4, HeadcountSweepEvent.CORRECTION, count = 6, supersedes = eid(3), reason = "one person counted twice"),
                event(5, HeadcountSweepEvent.CHECKPOINT, count = 8),
            ),
        )
        assertTrue(corrected.isValid, corrected.errors.toString())
        assertEquals(1, corrected.corrections)
        val fixed = corrected.checkpoints.single { it.correctedBy != null }
        assertEquals(6, fixed.count)
        assertEquals(3, fixed.sequence)
        assertEquals(eid(4), fixed.correctedBy)
        assertEquals(eid(5), corrected.correctableEventId)
    }

    @Test
    fun nothingFollowsAClose() {
        val state = HeadcountSweepReplay.replay(
            stream(
                event(1, HeadcountSweepEvent.START),
                event(2, HeadcountSweepEvent.CHECKPOINT, count = 2),
                event(3, HeadcountSweepEvent.ABORT, reason = "called away", observers = null),
                event(4, HeadcountSweepEvent.CHECKPOINT, count = 3),
            ),
        )
        assertEquals(SweepPhase.ABORTED, state.phase)
        assertEquals(listOf("event_after_close"), state.errors.map { it.code })
        assertEquals("called away", state.abortReason)
    }

    @Test
    fun zeroOtherPeopleIsALegalFinalisedSweep() {
        val state = HeadcountSweepReplay.replay(
            stream(
                event(1, HeadcountSweepEvent.START),
                event(2, HeadcountSweepEvent.CHECKPOINT, count = 0),
                event(3, HeadcountSweepEvent.FINALISE, count = 0, coverage = "complete"),
            ),
        )
        assertEquals(SweepPhase.FINALISED, state.phase)
        assertEquals(0, state.finalCount)
        assertEquals(1, state.observersInArea)
    }

    @Test
    fun aForeignSweepAndAMissingStartAreRefused() {
        val foreign = HeadcountSweepReplay.replay(
            stream(
                event(1, HeadcountSweepEvent.START),
                event(2, HeadcountSweepEvent.CHECKPOINT, count = 1, sweepId = "5e000000-0000-4000-8000-000000000002"),
            ),
        )
        assertEquals(listOf("foreign_sweep"), foreign.errors.map { it.code })
        val headless = HeadcountSweepReplay.replay(stream(event(1, HeadcountSweepEvent.CHECKPOINT, count = 2)))
        assertEquals(listOf("start_expected"), headless.errors.map { it.code })
        assertEquals(SweepPhase.NOT_STARTED, headless.phase)
    }

    @Test
    fun partialCoverageNeedsAReason() {
        val bad = event(3, HeadcountSweepEvent.FINALISE, count = 3, coverage = "partial")
        assertTrue(bad.problems().any { "coverage_reason" in it })
        val good = bad.copy(coverageReason = "alcove closed")
        assertTrue(good.problems().isEmpty(), good.problems().toString())
        assertTrue("coverage_reason" in good.toJson())
    }
}
