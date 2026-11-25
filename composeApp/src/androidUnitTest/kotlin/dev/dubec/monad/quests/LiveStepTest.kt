package dev.dubec.monad.quests

import dev.dubec.monad.quests.data.adapter.LiveWire
import dev.dubec.monad.quests.data.dto.LiveSyncResponseDto
import dev.dubec.monad.quests.domain.live.LiveAir
import dev.dubec.monad.quests.domain.live.LiveAnswer
import dev.dubec.monad.quests.domain.live.LiveEvent
import dev.dubec.monad.quests.domain.live.LiveGateway
import dev.dubec.monad.quests.domain.live.LiveRecorder
import dev.dubec.monad.quests.domain.live.LiveRefusedException
import dev.dubec.monad.quests.domain.live.LiveReply
import dev.dubec.monad.quests.domain.live.LiveScreen
import dev.dubec.monad.quests.domain.live.LiveStepController
import dev.dubec.monad.quests.domain.live.LiveWidget
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's half of a live step: the wire, the queue, and what reaches the recording. */
class LiveStepTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- wire ------------------------------------------------------------------------------

    @Test
    fun `every widget the server can send is understood`() {
        val reply = LiveWire.reply(
            json.decodeFromString(
                LiveSyncResponseDto.serializer(),
                """
                {"protocol":"monad-live/v1","run_id":"r","engine":"radio_hunt","phase":"seek","poll_ms":1000,"done":false,"result":null,
                 "acked":["e1"],"server_time_ms":1,"view":{"title":"Treasure 1 of 3","widgets":[
                  {"type":"text","text":"Walk.","style":"hint"},
                  {"type":"meter","value":0.42,"label":"Warm","tone":"warm","trend":"up"},
                  {"type":"meter","value":null,"label":"Listening…","tone":"none","trend":"none"},
                  {"type":"stats","items":[{"label":"Found","value":"2"}]},
                  {"type":"scan","id":"scan-1","label":"Scan","hint":"Point."},
                  {"type":"number","id":"count-1","prompt":"How many?","min":0,"max":80,"initial":0,"record":"headcount"},
                  {"type":"choice","id":"c","prompt":"Which?","options":[{"value":"a","label":"A"}]},
                  {"type":"hold","id":"hold-1","seconds":30,"label":"Still","target":{"value":"v","label":"l","room":"r","kind":"card"}},
                  {"type":"button","id":"skip-1","label":"Skip","style":"secondary"},
                  {"type":"progress","value":1,"total":3,"label":"found"},
                  {"type":"hologram","id":"x"}]}}
                """.trimIndent(),
            ),
        )

        val widgets = reply.screen.widgets
        assertEquals(10, widgets.size, "an unknown widget is dropped, the rest of the screen stands")
        assertEquals(LiveWidget.Meter(0.42f, "Warm", LiveWidget.Meter.Tone.WARM, LiveWidget.Meter.Trend.UP), widgets[1])
        assertNull(assertIs<LiveWidget.Meter>(widgets[2]).value)
        assertTrue(assertIs<LiveWidget.Number>(widgets[5]).headcount)
        assertEquals("card", assertIs<LiveWidget.Hold>(widgets[7]).target?.kind)
        assertEquals(setOf("e1"), reply.acked)
        assertNull(reply.screen.resultJson)
    }

    @Test
    fun `an event carries its widget, its value and both clocks`() {
        val request = LiveWire.request(
            listOf(
                LiveEvent("e1", "scan-1", LiveAnswer.Scanned("https://monad.dubec.dev/m/MONAD-FP-01"), 10, 20),
                LiveEvent("e2", "count-1", LiveAnswer.Counted(7), 11, 21),
                LiveEvent("e3", "hold-1", LiveAnswer.Held(30), 12, 22),
                LiveEvent("e4", "skip-1", LiveAnswer.Tapped, 13, 23),
            ),
            LiveAir("uuid", true),
        )

        assertEquals(listOf("scan", "answer", "hold_done", "tap"), request.events.map { it.kind })
        assertEquals("""{"widget":"count-1","value":7}""", request.events[1].data.toString())
        assertEquals("""{"widget":"hold-1","seconds":30}""", request.events[2].data.toString())
        assertEquals(20, request.events[0].monoNs)
        assertEquals("uuid", request.subject)
    }

    // ---- the loop --------------------------------------------------------------------------

    @Test
    fun `an answer lost on the way is resent once with the same id and then acknowledged`() = runBlocking {
        val gateway = FakeGateway(failFirstWithEvents = true)
        val recorder = FakeRecorder()
        val controller = controller(gateway, recorder)
        val loop = launch { controller.run() }
        awaitScreen(controller)

        val count = LiveWidget.Number("count-1", "How many?", 0, 80, 0, headcount = true)
        controller.submit(count, LiveAnswer.Counted(5))
        controller.submit(count, LiveAnswer.Counted(6)) // a double tap
        withTimeout(8_000) { while (gateway.delivered.isEmpty()) delay(20) }
        loop.cancelAndJoin()

        val sent = gateway.calls.flatten()
        assertEquals(1, sent.map { it.id }.toSet().size, "one answer, however often it is sent")
        assertTrue(sent.size >= 2, "the failed call is repeated")
        assertEquals(LiveAnswer.Counted(5), gateway.delivered.single().answer)
        assertEquals(listOf("answer"), recorder.events)
        assertEquals(listOf(5 to true), recorder.headcounts)
    }

    @Test
    fun `a scan may be repeated`() = runBlocking {
        val gateway = FakeGateway()
        val controller = controller(gateway, FakeRecorder())
        val loop = launch { controller.run() }
        awaitScreen(controller)
        val scan = LiveWidget.Scan("scan-1", "Scan", "")
        controller.submit(scan, LiveAnswer.Scanned("a"))
        controller.submit(scan, LiveAnswer.Scanned("b"))
        withTimeout(5_000) { while (gateway.delivered.size < 2) delay(20) }
        loop.cancelAndJoin()

        assertEquals(listOf("a", "b"), gateway.delivered.map { (it.answer as LiveAnswer.Scanned).code })
    }

    @Test
    fun `a final no ends the loop and is shown`() = runBlocking {
        val controller = controller(FakeGateway(refuse = 409), FakeRecorder())
        withTimeout(5_000) { controller.run() }

        assertEquals(409, controller.state.value.refusedStatus)
    }

    @Test
    fun `a finished step ends the loop with its result`() = runBlocking {
        val controller = controller(FakeGateway(done = true), FakeRecorder())
        withTimeout(5_000) { controller.run() }

        assertEquals("""{"found":3}""", controller.state.value.screen?.resultJson)
    }

    @Test
    fun `the step scanner leaves the camera to a running tracker`() {
        val text = File("src/commonMain/kotlin/dev/dubec/monad/quests/presentation/components/CardScanner.kt").readText()

        assertTrue(text.indexOf("if (trackerOwnsCamera)") in 0 until text.indexOf("QrScanner("))
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun controller(gateway: LiveGateway, recorder: LiveRecorder) = LiveStepController(
        stepCompletionId = "step",
        gateway = gateway,
        recorder = recorder,
        air = { LiveAir("uuid", true) },
        wallMillis = { 1 },
        monotonicNanos = { 2 },
    )

    private suspend fun awaitScreen(controller: LiveStepController) =
        withTimeout(5_000) { while (controller.state.value.screen == null) delay(10) }

    private class FakeGateway(
        private val failFirstWithEvents: Boolean = false,
        private val refuse: Int? = null,
        private val done: Boolean = false,
    ) : LiveGateway {
        val calls = mutableListOf<List<LiveEvent>>()
        val delivered = mutableListOf<LiveEvent>()
        private var failed = false

        override suspend fun sync(stepCompletionId: String, events: List<LiveEvent>, air: LiveAir): LiveReply {
            refuse?.let { throw LiveRefusedException(it) }
            calls += events
            if (failFirstWithEvents && events.isNotEmpty() && !failed) {
                failed = true
                throw IOException("offline")
            }
            delivered += events.filter { e -> delivered.none { it.id == e.id } }
            return LiveReply(
                LiveScreen("seek", "t", emptyList(), 300, done, if (done) """{"found":3}""" else null),
                events.map { it.id }.toSet(),
            )
        }
    }

    private class FakeRecorder : LiveRecorder {
        val events = mutableListOf<String>()
        val headcounts = mutableListOf<Pair<Int, Boolean>>()

        override suspend fun event(stepCompletionId: String, event: LiveEvent) {
            events += event.answer.kind
        }

        override suspend fun headcount(prompt: String, count: Int, onAir: Boolean) {
            headcounts += count to onAir
        }

        override suspend fun holdStarted(target: LiveWidget.Hold.Target) = Unit

        override suspend fun holdEnded(target: LiveWidget.Hold.Target, seconds: Int) = Unit
    }
}
