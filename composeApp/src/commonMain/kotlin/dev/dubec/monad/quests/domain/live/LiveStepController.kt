package dev.dubec.monad.quests.domain.live

import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** What the screen shows. */
data class LiveStepState(
    val screen: LiveScreen? = null,
    /** The last sync did not get through. The screen stays; it may be old. */
    val offline: Boolean = false,
    /** Set when the server gave a final no. The loop has ended. */
    val refusedStatus: Int? = null,
    /** Inputs already answered and not yet replaced by the server, so a button is not tapped twice. */
    val answered: Set<String> = emptySet(),
)

/**
 * Runs one live step: poll, send what happened, show what comes back.
 *
 * The server owns the state, so this class holds only a queue of unsent events. A lost answer is
 * resent with the same id and handled once. After an app restart the first sync shows the server's
 * state. The queue is memory only: an event that was never delivered survives as a marker in the
 * recording, and the participant answers again.
 */
class LiveStepController(
    private val stepCompletionId: String,
    private val gateway: LiveGateway,
    private val recorder: LiveRecorder,
    private val air: () -> LiveAir,
    private val wallMillis: () -> Long,
    private val monotonicNanos: () -> Long,
) {
    private val _state = MutableStateFlow(LiveStepState())
    val state: StateFlow<LiveStepState> = _state.asStateFlow()

    private val queue = MutableStateFlow<List<LiveEvent>>(emptyList())
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Polls until the step is done or finally refused. Cancel the coroutine to stop it. */
    suspend fun run() {
        var failures = 0
        while (true) {
            val batch = queue.value.take(MAX_BATCH)
            try {
                val reply = gateway.sync(stepCompletionId, batch, air())
                failures = 0
                queue.update { pending -> pending.filterNot { it.id in reply.acked } }
                _state.update { it.copy(screen = reply.screen, offline = false, answered = stillOpen(it, reply.screen)) }
                if (reply.screen.done) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: LiveRefusedException) {
                _state.update { it.copy(refusedStatus = e.status) }
                return
            } catch (e: Exception) {
                failures++
                _state.update { it.copy(offline = true) }
            }
            val pause = when {
                failures > 0 -> (BACKOFF_MILLIS * failures).coerceAtMost(MAX_BACKOFF_MILLIS)
                queue.value.isNotEmpty() -> 0L
                else -> _state.value.screen?.pollMillis ?: BACKOFF_MILLIS
            }
            withTimeoutOrNull(pause) { wake.receive() }
        }
    }

    /** Queue what the participant did and send it now. A second answer to one input is ignored. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun submit(widget: LiveWidget.Input, answer: LiveAnswer) {
        // A scan may be repeated: the wrong card first, the right one after it.
        val once = answer !is LiveAnswer.Scanned
        if (once && widget.id in _state.value.answered) return
        if (once) _state.update { it.copy(answered = it.answered + widget.id) }

        val event = LiveEvent(
            id = Uuid.random().toString(),
            widgetId = widget.id,
            answer = answer,
            wallMillis = wallMillis(),
            monotonicNanos = monotonicNanos(),
        )
        recorder.event(stepCompletionId, event)
        if (widget is LiveWidget.Number && widget.headcount && answer is LiveAnswer.Counted) {
            recorder.headcount(widget.prompt, answer.value, air().broadcasting)
        }
        queue.update { it + event }
        wake.trySend(Unit)
    }

    /** An input the server still shows keeps its "answered" mark only while its event is unsent. */
    private fun stillOpen(before: LiveStepState, screen: LiveScreen): Set<String> {
        val shown = screen.widgets.filterIsInstance<LiveWidget.Input>().map { it.id }.toSet()
        val unsent = queue.value.map { it.widgetId }.toSet()
        return before.answered.filter { it in shown && it in unsent }.toSet()
    }

    private companion object {
        const val MAX_BATCH = 50
        const val BACKOFF_MILLIS = 1_500L
        const val MAX_BACKOFF_MILLIS = 8_000L
    }
}
