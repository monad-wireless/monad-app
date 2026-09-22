package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One event of a cumulative room sweep — `monad-app/headcount-marker/v3` (IP-162).
 *
 * **This is a data contract**, on the same terms as [HeadcountMarkerPayload] and
 * [WaypointMarkerPayload]: the backend and the analysis join on these names, and the schema that
 * owns them lives in `monad-knowledge/monad_knowledge/lab/contracts/schemas/headcount-marker-v3.schema.json`.
 * Renaming a field here is a silent corpus split, not a compile error.
 *
 * Why a hand-built [JsonObject] rather than `@Serializable`: the schema forbids the fields that do
 * not belong to an event type (a `start` has no `count`, an `abort` has no `coverage`), and it
 * requires `observers_in_area` to be *present* on `start` and `finalise` even when it is null —
 * "unknown" is a recorded answer. `kotlinx.serialization` cannot express "absent for this type,
 * present-and-nullable for that one" with defaults alone, and a reader that tolerated the wrong
 * shape would be the integer-label fallback all over again. [toJson] writes exactly the schema's
 * shape per type, and [encode] writes it as canonical bytes so an identical retry hashes identical.
 *
 * What a v3 event means, in one paragraph. The participant walks one named room once, counting
 * each other person once. A `checkpoint` records the **running total so far** — cumulative, never
 * summed with another checkpoint. A `correction` supersedes an accepted checkpoint or correction
 * and keeps the original. One `finalise` records the final total and the coverage, stability,
 * activity and duplicate-risk declarations; one `abort` saves an interrupted run as partial
 * evidence. v1/v2 payloads are partial views from one spot and are never read as any of this.
 */
data class HeadcountSweepEvent(
    val eventId: String,
    val sweepId: String,
    val sequence: Int,
    val eventType: String,
    val recordingSessionId: String,
    val enrollmentId: String,
    val stepCompletionId: String,
    val protocolId: String,
    val protocolSha256: String,
    val roomId: String,
    val coverageVersion: String,
    /** App-observed advertising state at the instant of the event; null only when unavailable. */
    val onAir: Boolean?,
    val prompt: String,
    val count: Int? = null,
    val supersedesEventId: String? = null,
    val reason: String? = null,
    val coverage: String? = null,
    val coverageReason: String? = null,
    val occupancyStability: String? = null,
    val activity: String? = null,
    val duplicateRisk: String? = null,
    /** Known number of counting observers in the area, the participant included; null = unknown. */
    val observersInArea: Int? = null,
    val mode: String = MODE,
) {
    val isStart: Boolean get() = eventType == START
    val isClose: Boolean get() = eventType == FINALISE || eventType == ABORT

    /** The schema's shape for this event type, and nothing outside it. */
    fun toJson(): JsonObject {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "schema" to JsonPrimitive(SCHEMA),
            "event_id" to JsonPrimitive(eventId),
            "sweep_id" to JsonPrimitive(sweepId),
            "sequence" to JsonPrimitive(sequence),
            "event_type" to JsonPrimitive(eventType),
            "recording_session_id" to JsonPrimitive(recordingSessionId),
            "enrollment_id" to JsonPrimitive(enrollmentId),
            "step_completion_id" to JsonPrimitive(stepCompletionId),
            "protocol_id" to JsonPrimitive(protocolId),
            "protocol_sha256" to JsonPrimitive(protocolSha256),
            "mode" to JsonPrimitive(mode),
            "room_id" to JsonPrimitive(roomId),
            "coverage_version" to JsonPrimitive(coverageVersion),
            "on_air" to (onAir?.let { JsonPrimitive(it) } ?: JsonNull),
            "prompt" to JsonPrimitive(prompt),
        )
        when (eventType) {
            START -> {
                fields["observer_excluded"] = JsonPrimitive(true)
                fields["observers_in_area"] = observersInArea?.let { JsonPrimitive(it) } ?: JsonNull
            }
            CHECKPOINT -> {
                fields["count"] = JsonPrimitive(count ?: error("a checkpoint carries a count"))
            }
            CORRECTION -> {
                fields["count"] = JsonPrimitive(count ?: error("a correction carries a count"))
                fields["supersedes_event_id"] = JsonPrimitive(supersedesEventId ?: error("a correction names what it supersedes"))
                fields["reason"] = JsonPrimitive(reason ?: error("a correction carries a reason"))
            }
            FINALISE -> {
                fields["count"] = JsonPrimitive(count ?: error("a finalise carries a count"))
                fields["coverage"] = JsonPrimitive(coverage ?: error("a finalise declares coverage"))
                if (coverage != COVERAGE_COMPLETE) {
                    fields["coverage_reason"] = JsonPrimitive(coverageReason ?: error("incomplete coverage carries a reason"))
                }
                fields["occupancy_stability"] = JsonPrimitive(occupancyStability ?: error("a finalise declares stability"))
                fields["activity"] = JsonPrimitive(activity ?: error("a finalise declares activity"))
                fields["duplicate_risk"] = JsonPrimitive(duplicateRisk ?: error("a finalise declares duplicate risk"))
                fields["observer_excluded"] = JsonPrimitive(true)
                fields["observers_in_area"] = observersInArea?.let { JsonPrimitive(it) } ?: JsonNull
            }
            ABORT -> {
                fields["reason"] = JsonPrimitive(reason ?: error("an abort carries a reason"))
            }
            else -> error("unknown event type $eventType")
        }
        return JsonObject(fields)
    }

    /** Canonical bytes — what goes into `markers.tsv` `payload_json` and what the digest is over. */
    fun encode(): String = CanonicalJson.encode(toJson())

    fun digest(): String = CanonicalJson.sha256(toJson())

    companion object {
        const val SCHEMA: String = "monad-app/headcount-marker/v3"
        const val MODE: String = "room_sweep_cumulative"

        const val START = "start"
        const val CHECKPOINT = "checkpoint"
        const val CORRECTION = "correction"
        const val FINALISE = "finalise"
        const val ABORT = "abort"

        const val COVERAGE_COMPLETE = "complete"
        const val COVERAGE_PARTIAL = "partial"
        const val COVERAGE_UNKNOWN = "unknown"
        val COVERAGES = listOf(COVERAGE_COMPLETE, COVERAGE_PARTIAL, COVERAGE_UNKNOWN)
        val STABILITIES = listOf("stable", "changed", "unknown")
        val ACTIVITIES = listOf("seated", "moving", "mixed", "unknown")
        val DUPLICATE_RISKS = listOf("none_observed", "possible", "unknown")

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse a marker payload as a v3 event, or null when it is not one.
         *
         * Dispatches on the `schema` string first: a v1/v2 payload, a payload with no schema, or a
         * foreign schema returns null here and is never coerced. A v3 payload that fails its own
         * type's required fields also returns null, because a half-parsed event is worse than a
         * refused one — the raw row stays in `markers.tsv` either way.
         */
        fun parse(payload: String?): HeadcountSweepEvent? {
            if (payload.isNullOrBlank()) return null
            val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
            return fromJson(obj)
        }

        fun fromJson(obj: JsonObject): HeadcountSweepEvent? {
            if (obj["schema"]?.jsonPrimitive?.contentOrNull != SCHEMA) return null
            fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun int(key: String): Int? = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            fun bool(key: String): Boolean? = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            val eventType = str("event_type") ?: return null
            val onAirElement = obj["on_air"] ?: return null
            val onAir = if (onAirElement is JsonNull) null else bool("on_air") ?: return null
            val event = HeadcountSweepEvent(
                eventId = str("event_id") ?: return null,
                sweepId = str("sweep_id") ?: return null,
                sequence = int("sequence") ?: return null,
                eventType = eventType,
                recordingSessionId = str("recording_session_id") ?: return null,
                enrollmentId = str("enrollment_id") ?: return null,
                stepCompletionId = str("step_completion_id") ?: return null,
                protocolId = str("protocol_id") ?: return null,
                protocolSha256 = str("protocol_sha256") ?: return null,
                mode = str("mode") ?: return null,
                roomId = str("room_id") ?: return null,
                coverageVersion = str("coverage_version") ?: return null,
                onAir = onAir,
                prompt = str("prompt") ?: return null,
                count = int("count"),
                supersedesEventId = str("supersedes_event_id"),
                reason = str("reason"),
                coverage = str("coverage"),
                coverageReason = str("coverage_reason"),
                occupancyStability = str("occupancy_stability"),
                activity = str("activity"),
                duplicateRisk = str("duplicate_risk"),
                observersInArea = int("observers_in_area"),
            )
            return event.takeIf { it.problems().isEmpty() }
        }
    }

    /**
     * The per-type shape rules the schema carries, as reasons. Empty means well-formed.
     *
     * Structural only — sequence order, identity agreement and the count-decrease rule are the
     * replay's business ([HeadcountSweepReplay]), because they are facts about the stream, not
     * about one event.
     */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        if (mode != MODE) out += "mode: $mode is not $MODE"
        if (sequence < 1) out += "sequence: must be 1 or more"
        if (count != null && count < 0) out += "count: must not be negative"
        val hasCount = count != null
        when (eventType) {
            START -> {
                if (hasCount) out += "start: carries no count"
                if (supersedesEventId != null || reason != null || coverage != null) out += "start: carries no correction, reason or coverage"
            }
            CHECKPOINT -> {
                if (!hasCount) out += "checkpoint: count is required"
                if (supersedesEventId != null || reason != null || coverage != null) out += "checkpoint: carries no correction, reason or coverage"
            }
            CORRECTION -> {
                if (!hasCount) out += "correction: count is required"
                if (supersedesEventId.isNullOrBlank()) out += "correction: supersedes_event_id is required"
                if (reason.isNullOrBlank()) out += "correction: reason is required"
                if (coverage != null) out += "correction: carries no coverage"
            }
            FINALISE -> {
                if (!hasCount) out += "finalise: count is required"
                if (coverage !in COVERAGES) out += "finalise: coverage must be one of $COVERAGES"
                if (coverage != COVERAGE_COMPLETE && coverageReason.isNullOrBlank()) out += "finalise: incomplete coverage needs coverage_reason"
                if (coverage == COVERAGE_COMPLETE && coverageReason != null) out += "finalise: complete coverage carries no coverage_reason"
                if (occupancyStability !in STABILITIES) out += "finalise: occupancy_stability must be one of $STABILITIES"
                if (activity !in ACTIVITIES) out += "finalise: activity must be one of $ACTIVITIES"
                if (duplicateRisk !in DUPLICATE_RISKS) out += "finalise: duplicate_risk must be one of $DUPLICATE_RISKS"
                if (supersedesEventId != null || reason != null) out += "finalise: carries no correction or reason"
            }
            ABORT -> {
                if (reason.isNullOrBlank()) out += "abort: reason is required"
                if (hasCount || coverage != null || supersedesEventId != null) out += "abort: carries no count, coverage or correction"
            }
            else -> out += "event_type: unknown $eventType"
        }
        if (observersInArea != null && observersInArea < 1) out += "observers_in_area: must be 1 or more when known"
        return out
    }
}

/** One accepted running total, after any correction that superseded it. */
data class SweepCheckpoint(
    val eventId: String,
    val sequence: Int,
    val count: Int,
    val monotonicNanos: Long?,
    val correctedBy: String? = null,
)

data class SweepError(
    val code: String,
    val sequence: Int?,
    val eventId: String?,
    val detail: String,
)

enum class SweepPhase(val wire: String) {
    NOT_STARTED("not_started"),
    ACTIVE("active"),
    FINALISED("finalised"),
    ABORTED("aborted"),
}

/**
 * What a stream of v3 events amounts to — the on-device twin of
 * `monad_knowledge.lab.contracts.counting.project_sweep`.
 *
 * The app does not display or persist this; it *is* the sweep state the UI renders, rebuilt from
 * the durable marker log on every append and on every restart. Holding a counter in Compose
 * `remember` beside the log is how a killed process comes back with a number the log does not
 * support, so nothing here is remembered: the events are, and this is derived.
 */
data class SweepState(
    val sweepId: String? = null,
    val phase: SweepPhase = SweepPhase.NOT_STARTED,
    val eventsSeen: Int = 0,
    val eventsAccepted: Int = 0,
    val duplicatesIgnored: Int = 0,
    val identity: HeadcountSweepEvent? = null,
    val startMonotonicNanos: Long? = null,
    val endMonotonicNanos: Long? = null,
    val checkpoints: List<SweepCheckpoint> = emptyList(),
    val corrections: Int = 0,
    val runningTotal: Int? = null,
    val finalCount: Int? = null,
    val coverage: String? = null,
    val coverageReason: String? = null,
    val occupancyStability: String? = null,
    val activity: String? = null,
    val duplicateRisk: String? = null,
    val observersInArea: Int? = null,
    val abortReason: String? = null,
    val lastEventId: String? = null,
    val finalEventId: String? = null,
    val errors: List<SweepError> = emptyList(),
) {
    /** Contiguous from 1; the next append takes this. */
    val nextSequence: Int get() = eventsAccepted + 1

    val isValid: Boolean get() = errors.isEmpty()

    /** The most recent checkpoint or correction a new correction may supersede, or null. */
    val correctableEventId: String? get() = lastCorrectable
    internal var lastCorrectable: String? = null
}

object HeadcountSweepReplay {

    /**
     * Replay events in the order given. A refused event is recorded and skipped; replay continues.
     *
     * Each item is the event plus its envelope `mono_ns` (null when the envelope is unknown, as in a
     * unit test).
     */
    fun replay(events: List<Pair<Long?, HeadcountSweepEvent>>): SweepState {
        var state = SweepState()
        val bySequence = mutableMapOf<Int, String>()
        val accepted = mutableMapOf<String, HeadcountSweepEvent>()
        val superseded = mutableSetOf<String>()
        var lastMono: Long? = null
        var lastCorrectable: String? = null

        fun refuse(code: String, event: HeadcountSweepEvent, detail: String) {
            state = state.copy(errors = state.errors + SweepError(code, event.sequence, event.eventId, detail))
        }

        for ((monoNs, event) in events) {
            state = state.copy(eventsSeen = state.eventsSeen + 1)
            val shape = event.problems()
            if (shape.isNotEmpty()) {
                refuse("schema_invalid", event, shape.joinToString("; "))
                continue
            }
            if (state.sweepId == null) {
                state = state.copy(sweepId = event.sweepId)
            } else if (event.sweepId != state.sweepId) {
                refuse("foreign_sweep", event, "sweep_id ${event.sweepId} is not ${state.sweepId}")
                continue
            }
            val digest = event.digest()
            val previous = bySequence[event.sequence]
            if (previous != null) {
                if (previous == digest) {
                    state = state.copy(duplicatesIgnored = state.duplicatesIgnored + 1)
                } else {
                    refuse("sequence_conflict", event, "sequence ${event.sequence} already accepted with different content")
                }
                continue
            }
            if (event.sequence != state.nextSequence) {
                refuse("sequence_gap", event, "expected sequence ${state.nextSequence}, got ${event.sequence}")
                continue
            }
            if (event.eventId in accepted) {
                refuse("event_id_reused", event, "event_id already names another sequence")
                continue
            }
            if (state.phase == SweepPhase.NOT_STARTED && !event.isStart) {
                refuse("start_expected", event, "first event must be start, got ${event.eventType}")
                continue
            }
            if (state.phase == SweepPhase.FINALISED || state.phase == SweepPhase.ABORTED) {
                refuse("event_after_close", event, "sweep is ${state.phase.wire}")
                continue
            }
            if (state.phase == SweepPhase.ACTIVE && event.isStart) {
                refuse("duplicate_start", event, "sweep already started")
                continue
            }
            val identity = state.identity
            if (identity != null && !sameIdentity(identity, event)) {
                refuse("identity_mismatch", event, "identity fields differ from the start event")
                continue
            }
            if (monoNs != null) {
                val last = lastMono
                if (last != null && monoNs < last) {
                    refuse("time_not_monotonic", event, "mono_ns $monoNs precedes the previous event")
                    continue
                }
            }
            val count = event.count
            if ((event.eventType == HeadcountSweepEvent.CHECKPOINT || event.eventType == HeadcountSweepEvent.FINALISE) &&
                count != null && state.runningTotal != null && count < state.runningTotal!!
            ) {
                refuse(
                    "count_decreased_without_correction",
                    event,
                    "total fell from ${state.runningTotal} to $count; a decrease needs a correction with a reason",
                )
                continue
            }
            var rootId: String? = null
            if (event.eventType == HeadcountSweepEvent.CORRECTION) {
                val targetId = event.supersedesEventId!!
                if (targetId == event.eventId) {
                    refuse("correction_cycle", event, "a correction cannot supersede itself")
                    continue
                }
                val target = accepted[targetId]
                if (target == null ||
                    (target.eventType != HeadcountSweepEvent.CHECKPOINT && target.eventType != HeadcountSweepEvent.CORRECTION)
                ) {
                    refuse("supersedes_unknown", event, "$targetId is not an accepted checkpoint or correction")
                    continue
                }
                if (targetId in superseded) {
                    refuse("supersedes_superseded", event, "$targetId was already superseded")
                    continue
                }
                var root = targetId
                while (accepted[root]?.eventType == HeadcountSweepEvent.CORRECTION) {
                    root = accepted[root]!!.supersedesEventId!!
                }
                rootId = root
                superseded += targetId
            }

            // ---- accept -------------------------------------------------------------------
            bySequence[event.sequence] = digest
            accepted[event.eventId] = event
            if (monoNs != null) lastMono = monoNs
            state = state.copy(eventsAccepted = state.eventsAccepted + 1, lastEventId = event.eventId)
            when (event.eventType) {
                HeadcountSweepEvent.START -> state = state.copy(
                    phase = SweepPhase.ACTIVE,
                    identity = event,
                    startMonotonicNanos = monoNs,
                    observersInArea = event.observersInArea,
                )
                HeadcountSweepEvent.CHECKPOINT -> {
                    state = state.copy(
                        checkpoints = state.checkpoints + SweepCheckpoint(event.eventId, event.sequence, count!!, monoNs),
                        runningTotal = count,
                    )
                    lastCorrectable = event.eventId
                }
                HeadcountSweepEvent.CORRECTION -> {
                    val corrected = state.checkpoints.map { cp ->
                        if (cp.eventId == rootId) cp.copy(count = count!!, correctedBy = event.eventId) else cp
                    }
                    state = state.copy(
                        checkpoints = corrected,
                        corrections = state.corrections + 1,
                        runningTotal = corrected.maxOfOrNull { it.count },
                    )
                    lastCorrectable = event.eventId
                }
                HeadcountSweepEvent.FINALISE -> state = state.copy(
                    phase = SweepPhase.FINALISED,
                    finalCount = count,
                    finalEventId = event.eventId,
                    endMonotonicNanos = monoNs,
                    coverage = event.coverage,
                    coverageReason = event.coverageReason,
                    occupancyStability = event.occupancyStability,
                    activity = event.activity,
                    duplicateRisk = event.duplicateRisk,
                    observersInArea = event.observersInArea,
                )
                HeadcountSweepEvent.ABORT -> state = state.copy(
                    phase = SweepPhase.ABORTED,
                    finalEventId = event.eventId,
                    endMonotonicNanos = monoNs,
                    abortReason = event.reason,
                )
            }
        }
        state.lastCorrectable = lastCorrectable
        return state
    }

    private fun sameIdentity(a: HeadcountSweepEvent, b: HeadcountSweepEvent): Boolean =
        a.recordingSessionId == b.recordingSessionId &&
            a.enrollmentId == b.enrollmentId &&
            a.stepCompletionId == b.stepCompletionId &&
            a.protocolId == b.protocolId &&
            a.protocolSha256 == b.protocolSha256 &&
            a.mode == b.mode &&
            a.roomId == b.roomId &&
            a.coverageVersion == b.coverageVersion
}
