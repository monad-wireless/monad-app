package sk.martinvanco.monad.quests.presentation.components.steps

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import sk.martinvanco.monad.lab.data.LabSessionRepository
import sk.martinvanco.monad.lab.domain.CanonicalJson
import sk.martinvanco.monad.lab.domain.HeadcountSweepEvent
import sk.martinvanco.monad.lab.domain.HeadcountSweepReplay
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.SessionMarker
import sk.martinvanco.monad.lab.domain.SweepPhase
import sk.martinvanco.monad.lab.domain.SweepState
import sk.martinvanco.monad.quests.data.dto.ObserveConfig
import sk.martinvanco.monad.quests.data.dto.SweepRoom
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The state of one room sweep, held outside Compose and derived from the durable event log (IP-162).
 *
 * The previous widget kept its count in `remember` and launched the marker write without waiting
 * for it, so a refused write, a killed process or a second tap each left the screen and the log
 * disagreeing about how many people had been counted. Here every action is one event, appended
 * through [LabInstrument.markDurable], and the screen shows a number only after the append
 * returned: the [SweepState] the UI renders is [HeadcountSweepReplay] over the events that are
 * actually in the store, on every append and again on [attach] after a restart.
 *
 * Two rules the UI cannot break through this object. One append at a time — a second tap while
 * one is in flight is refused, not queued, so a double tap cannot become two checkpoints. And a
 * failed append keeps its event: [retry] re-sends the same `event_id` and `sequence`, so a retry
 * that in fact landed the first time is a duplicate the replay ignores rather than a new count.
 */
@OptIn(ExperimentalUuidApi::class)
class HeadcountSweepController(
    private val instrument: LabInstrument,
    private val repository: LabSessionRepository,
) {

    data class UiState(
        val attached: Boolean = false,
        val sweep: SweepState = SweepState(),
        val room: SweepRoom? = null,
        /** An append is in flight; every action is disabled until it returns. */
        val pending: Boolean = false,
        /** The last refused append, in words the participant can act on. */
        val lastError: String? = null,
        /** A refused event is waiting to be re-sent under the same identity. */
        val canRetry: Boolean = false,
        /** The state was rebuilt from events recorded before this attach — a restart or a re-entry. */
        val recovered: Boolean = false,
        /** No measurement session is running, so no event can be recorded. */
        val noSession: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val events = mutableListOf<Pair<Long?, HeadcountSweepEvent>>()
    private var pendingEvent: HeadcountSweepEvent? = null
    private var pendingLabel: String = ""
    private lateinit var config: ObserveConfig
    private lateinit var enrollmentId: String
    private lateinit var stepCompletionId: String

    /**
     * Bind to one step of one enrollment and rebuild the sweep from whatever the log already holds.
     *
     * Events from another step of the same recording are left alone: several sweeps may share a
     * recording and each keeps its own identity.
     */
    suspend fun attach(enrollmentId: String, stepCompletionId: String, config: ObserveConfig) {
        this.config = config
        this.enrollmentId = enrollmentId
        this.stepCompletionId = stepCompletionId
        val sessionId = instrument.recordingSessionId
        events.clear()
        if (sessionId != null) {
            events += repository.sweepEvents(sessionId)
                .filter { it.second.stepCompletionId == stepCompletionId }
                .map { (mono, event) -> mono as Long? to event }
        }
        val sweep = HeadcountSweepReplay.replay(events)
        _state.value = UiState(
            attached = true,
            sweep = sweep,
            room = sweep.identity?.let { start -> config.rooms.firstOrNull { it.roomId == start.roomId } },
            recovered = events.isNotEmpty(),
            noSession = sessionId == null,
        )
    }

    suspend fun start(room: SweepRoom, observersInArea: Int?, onAir: Boolean?): Result<Unit> =
        append(label = HeadcountSweepEvent.START) { sequence, sweepId ->
            event(sequence, sweepId, HeadcountSweepEvent.START, room, onAir).copy(observersInArea = observersInArea)
        }.onSuccess { _state.value = _state.value.copy(room = room) }

    suspend fun checkpoint(count: Int, onAir: Boolean?): Result<Unit> =
        append(label = count.toString()) { sequence, sweepId ->
            event(sequence, sweepId, HeadcountSweepEvent.CHECKPOINT, roomOrFail(), onAir).copy(count = count)
        }

    /** Correct the most recent checkpoint (or correction) to [count], with the reason the participant gave. */
    suspend fun correctLast(count: Int, reason: String, onAir: Boolean?): Result<Unit> {
        val target = _state.value.sweep.correctableEventId
            ?: return Result.failure(IllegalStateException("there is no checkpoint to correct yet"))
        return append(label = count.toString()) { sequence, sweepId ->
            event(sequence, sweepId, HeadcountSweepEvent.CORRECTION, roomOrFail(), onAir)
                .copy(count = count, supersedesEventId = target, reason = reason.trim())
        }
    }

    suspend fun finalise(
        count: Int,
        coverage: String,
        coverageReason: String?,
        occupancyStability: String,
        activity: String,
        duplicateRisk: String,
        observersInArea: Int?,
        onAir: Boolean?,
    ): Result<Unit> = append(label = count.toString()) { sequence, sweepId ->
        event(sequence, sweepId, HeadcountSweepEvent.FINALISE, roomOrFail(), onAir).copy(
            count = count,
            coverage = coverage,
            coverageReason = coverageReason?.trim()?.takeIf { it.isNotEmpty() && coverage != HeadcountSweepEvent.COVERAGE_COMPLETE },
            occupancyStability = occupancyStability,
            activity = activity,
            duplicateRisk = duplicateRisk,
            observersInArea = observersInArea,
        )
    }

    suspend fun abort(reason: String, onAir: Boolean?): Result<Unit> =
        append(label = HeadcountSweepEvent.ABORT) { sequence, sweepId ->
            event(sequence, sweepId, HeadcountSweepEvent.ABORT, roomOrFail(), onAir).copy(reason = reason.trim())
        }

    /** Re-send the event the last failed action minted, under the same identity. */
    suspend fun retry(): Result<Unit> {
        val event = pendingEvent ?: return Result.failure(IllegalStateException("nothing to retry"))
        return commit(event, pendingLabel)
    }

    /**
     * The typed summary the quest completion carries in `step_data` (IP-162 §2): a pointer to the
     * evidence, never a second count stream. The backend reconciles it against the uploaded
     * events by `(recording_session_id, sweep_id, final_event_id)`; the manifest digest is not
     * known here because the upload runs after the completion is submitted.
     */
    fun stepDataJson(): String? {
        val sweep = _state.value.sweep
        val start = sweep.identity ?: return null
        val fields = mapOf(
            "schema" to JsonPrimitive(SUMMARY_SCHEMA),
            "recording_session_id" to JsonPrimitive(start.recordingSessionId),
            "sweep_id" to JsonPrimitive(start.sweepId),
            "step_completion_id" to JsonPrimitive(start.stepCompletionId),
            "protocol_id" to JsonPrimitive(start.protocolId),
            "protocol_sha256" to JsonPrimitive(start.protocolSha256),
            "room_id" to JsonPrimitive(start.roomId),
            "coverage_version" to JsonPrimitive(start.coverageVersion),
            "phase" to JsonPrimitive(sweep.phase.wire),
            "final_event_id" to (sweep.finalEventId?.let { JsonPrimitive(it) } ?: JsonNull),
            "count" to (sweep.finalCount?.let { JsonPrimitive(it) } ?: JsonNull),
            "coverage" to (sweep.coverage?.let { JsonPrimitive(it) } ?: JsonNull),
            "occupancy_stability" to (sweep.occupancyStability?.let { JsonPrimitive(it) } ?: JsonNull),
            "events_accepted" to JsonPrimitive(sweep.eventsAccepted),
            "corrections" to JsonPrimitive(sweep.corrections),
        )
        return CanonicalJson.encode(JsonObject(fields))
    }

    // ---- internals -----------------------------------------------------------------------------

    private fun roomOrFail(): SweepRoom =
        _state.value.room ?: throw IllegalStateException("the sweep has no room; start it first")

    private fun event(
        sequence: Int,
        sweepId: String,
        type: String,
        room: SweepRoom,
        onAir: Boolean?,
    ): HeadcountSweepEvent = HeadcountSweepEvent(
        eventId = Uuid.random().toString(),
        sweepId = sweepId,
        sequence = sequence,
        eventType = type,
        recordingSessionId = instrument.recordingSessionId ?: "",
        enrollmentId = enrollmentId,
        stepCompletionId = stepCompletionId,
        protocolId = config.protocolId.orEmpty(),
        protocolSha256 = config.protocolSha256.orEmpty(),
        roomId = room.roomId,
        coverageVersion = room.coverageVersion,
        onAir = onAir,
        prompt = config.prompt,
    )

    private suspend fun append(
        label: String,
        build: (sequence: Int, sweepId: String) -> HeadcountSweepEvent,
    ): Result<Unit> {
        if (_state.value.pending) {
            return Result.failure(IllegalStateException("the previous count is still being recorded"))
        }
        if (pendingEvent != null) {
            return Result.failure(IllegalStateException("a count failed to record; retry it or the sweep cannot continue"))
        }
        val sweep = _state.value.sweep
        val sessionId = instrument.recordingSessionId
            ?: run {
                _state.value = _state.value.copy(noSession = true, lastError = "The measurement session is not running.")
                return Result.failure(IllegalStateException("no measurement session"))
            }
        val sweepId = sweep.sweepId ?: Uuid.random().toString()
        val event = runCatching { build(sweep.nextSequence, sweepId) }
            .getOrElse { return Result.failure(it) }
        val problems = event.problems()
        if (problems.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(problems.joinToString("; ")))
        }
        // Refused by the replay before it is written: an event the log would reject is not appended
        // and then explained; it is not appended.
        val preview = HeadcountSweepReplay.replay(events + (null to event))
        if (preview.errors.size > sweep.errors.size) {
            val error = preview.errors.last()
            return Result.failure(IllegalArgumentException("${error.code}: ${error.detail}"))
        }
        check(event.recordingSessionId == sessionId)
        return commit(event, label)
    }

    private suspend fun commit(event: HeadcountSweepEvent, label: String): Result<Unit> = mutex.withLock {
        _state.value = _state.value.copy(pending = true, lastError = null)
        val result = instrument.markDurable(
            kind = SessionMarker.Kind.HEADCOUNT,
            label = label,
            stepId = stepCompletionId,
            payload = event.encode(),
        )
        result.fold(
            onSuccess = { marker ->
                pendingEvent = null
                pendingLabel = ""
                events += marker.monotonicNanos to event
                _state.value = _state.value.copy(
                    sweep = HeadcountSweepReplay.replay(events),
                    pending = false,
                    canRetry = false,
                    lastError = null,
                    noSession = false,
                )
                Result.success(Unit)
            },
            onFailure = { error ->
                pendingEvent = event
                pendingLabel = label
                Napier.w("[lab] sweep event ${event.eventType} #${event.sequence} refused: ${error.message}")
                _state.value = _state.value.copy(
                    pending = false,
                    canRetry = true,
                    lastError = "Not recorded: ${error.message ?: "the store refused the write"}. Retry before continuing.",
                    noSession = instrument.recordingSessionId == null,
                )
                Result.failure(error)
            },
        )
    }

    companion object {
        const val SUMMARY_SCHEMA = "monad-lab/sweep-summary/v1"

        fun isClosed(phase: SweepPhase): Boolean = phase == SweepPhase.FINALISED || phase == SweepPhase.ABORTED
    }
}
