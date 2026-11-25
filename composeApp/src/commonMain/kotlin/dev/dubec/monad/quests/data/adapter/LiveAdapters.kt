package dev.dubec.monad.quests.data.adapter

import dev.dubec.monad.auth.data.repository.UserRepository
import dev.dubec.monad.core.data.remote.KtorClient
import dev.dubec.monad.lab.domain.HeadcountMarkerPayload
import dev.dubec.monad.lab.domain.LabInstrument
import dev.dubec.monad.lab.domain.SessionMarker
import dev.dubec.monad.quests.data.dto.LiveEventDto
import dev.dubec.monad.quests.data.dto.LiveSyncRequestDto
import dev.dubec.monad.quests.data.dto.LiveSyncResponseDto
import dev.dubec.monad.quests.domain.live.LiveAir
import dev.dubec.monad.quests.domain.live.LiveAnswer
import dev.dubec.monad.quests.domain.live.LiveEvent
import dev.dubec.monad.quests.domain.live.LiveGateway
import dev.dubec.monad.quests.domain.live.LiveRecorder
import dev.dubec.monad.quests.domain.live.LiveRefusedException
import dev.dubec.monad.quests.domain.live.LiveReply
import dev.dubec.monad.quests.domain.live.LiveScreen
import dev.dubec.monad.quests.domain.live.LiveWidget
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/** [LiveGateway] over the API. A hint is worth seconds, so the call is given up early. */
class LiveGatewayAdapter(
    private val ktor: KtorClient,
    private val users: UserRepository,
) : LiveGateway {

    override suspend fun sync(stepCompletionId: String, events: List<LiveEvent>, air: LiveAir): LiveReply {
        val token = users.getCurrentUser()?.token ?: throw LiveRefusedException(401)
        try {
            val response = ktor.client.post("/api/quest/live/$stepCompletionId") {
                timeout { requestTimeoutMillis = TIMEOUT_MILLIS }
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                setBody(LiveWire.request(events, air))
            }
            return LiveWire.reply(response.body<LiveSyncResponseDto>())
        } catch (e: ClientRequestException) {
            val status = e.response.status.value
            if (status in FINAL) throw LiveRefusedException(status)
            throw e
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 6_000L

        /** A retry of the same call gets the same answer. 408, 425 and 429 do not. */
        val FINAL = setOf(400, 401, 403, 404, 409, 422)
    }
}

/** Both directions of the wire, without a network, so the mapping is tested as a pure function. */
object LiveWire {

    fun request(events: List<LiveEvent>, air: LiveAir) = LiveSyncRequestDto(
        events = events.map { LiveEventDto(it.id, it.answer.kind, data(it), it.wallMillis, it.monotonicNanos) },
        subject = air.subject,
        broadcasting = air.broadcasting,
    )

    fun reply(dto: LiveSyncResponseDto) = LiveReply(
        screen = LiveScreen(
            phase = dto.phase,
            title = dto.view.title,
            widgets = dto.view.widgets.mapNotNull(::widget),
            pollMillis = dto.pollMs.coerceIn(300, 30_000),
            done = dto.done,
            resultJson = dto.result?.takeIf { it !is JsonNull }?.toString(),
        ),
        acked = dto.acked.toSet(),
    )

    fun data(event: LiveEvent): JsonObject = JsonObject(
        buildMap {
            put("widget", JsonPrimitive(event.widgetId))
            when (val answer = event.answer) {
                is LiveAnswer.Scanned -> put("value", JsonPrimitive(answer.code))
                is LiveAnswer.Counted -> put("value", JsonPrimitive(answer.value))
                is LiveAnswer.Chosen -> put("value", JsonPrimitive(answer.value))
                is LiveAnswer.Held -> put("seconds", JsonPrimitive(answer.seconds))
                LiveAnswer.Tapped -> Unit
            }
        }
    )

    /** Null for a widget this build does not know, or one without what it needs. */
    fun widget(raw: JsonObject): LiveWidget? {
        val id = raw.text("id")
        return when (raw.text("type")) {
            "text" -> LiveWidget.Text(
                text = raw.text("text") ?: return null,
                style = when (raw.text("style")) {
                    "hint" -> LiveWidget.Text.Style.HINT
                    "warning" -> LiveWidget.Text.Style.WARNING
                    "success" -> LiveWidget.Text.Style.SUCCESS
                    else -> LiveWidget.Text.Style.BODY
                },
            )

            "meter" -> LiveWidget.Meter(
                value = (raw["value"] as? JsonPrimitive)?.floatOrNull,
                label = raw.text("label").orEmpty(),
                tone = LiveWidget.Meter.Tone.entries.firstOrNull { it.name.equals(raw.text("tone"), true) }
                    ?: LiveWidget.Meter.Tone.NONE,
                trend = LiveWidget.Meter.Trend.entries.firstOrNull { it.name.equals(raw.text("trend"), true) }
                    ?: LiveWidget.Meter.Trend.NONE,
            )

            "stats" -> LiveWidget.Stats(
                raw.objects("items").map { LiveWidget.Stats.Item(it.text("label").orEmpty(), it.text("value").orEmpty()) },
            )

            "progress" -> LiveWidget.Progress(raw.int("value") ?: 0, raw.int("total") ?: 0, raw.text("label").orEmpty())

            "scan" -> LiveWidget.Scan(id ?: return null, raw.text("label").orEmpty(), raw.text("hint").orEmpty())

            "number" -> {
                val min = raw.int("min") ?: 0
                val max = (raw.int("max") ?: 100).coerceAtLeast(min)
                LiveWidget.Number(
                    id = id ?: return null,
                    prompt = raw.text("prompt").orEmpty(),
                    min = min,
                    max = max,
                    initial = (raw.int("initial") ?: min).coerceIn(min, max),
                    headcount = raw.text("record") == "headcount",
                )
            }

            "choice" -> LiveWidget.Choice(
                id = id ?: return null,
                prompt = raw.text("prompt").orEmpty(),
                options = raw.objects("options").mapNotNull { o ->
                    o.text("value")?.let { LiveWidget.Choice.Option(it, o.text("label") ?: it) }
                },
            )

            "hold" -> LiveWidget.Hold(
                id = id ?: return null,
                seconds = (raw.int("seconds") ?: return null).coerceIn(1, 600),
                label = raw.text("label").orEmpty(),
                target = (raw["target"] as? JsonObject)?.let { t ->
                    t.text("value")?.let { value ->
                        LiveWidget.Hold.Target(value, t.text("label").orEmpty(), t.text("room").orEmpty(), t.text("kind").orEmpty())
                    }
                },
            )

            "button" -> LiveWidget.Button(id ?: return null, raw.text("label").orEmpty(), raw.text("style") != "secondary")

            else -> null
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
}

/** [LiveRecorder] over the running lab session. Same markers a probe and an observe step write. */
class LabLiveRecorder(
    private val instrument: LabInstrument,
) : LiveRecorder {

    override suspend fun event(stepCompletionId: String, event: LiveEvent) = instrument.mark(
        kind = SessionMarker.Kind.ANNOTATION,
        label = "live:${event.answer.kind}",
        stepId = stepCompletionId,
        payload = JsonObject(LiveWire.data(event) + ("event_id" to JsonPrimitive(event.id))).toString(),
    )

    override suspend fun headcount(prompt: String, count: Int, onAir: Boolean) = instrument.mark(
        kind = SessionMarker.Kind.HEADCOUNT,
        label = "$count",
        stepId = "1",
        payload = Json.encodeToString(
            HeadcountMarkerPayload.serializer(),
            HeadcountMarkerPayload(count = count, reading = 1, ofReadings = 1, prompt = prompt, onAir = onAir),
        ),
    )

    override suspend fun holdStarted(target: LiveWidget.Hold.Target) {
        instrument.markWaypoint(
            code = target.value,
            annotation = target.label.ifBlank { null },
            room = target.room.ifBlank { null },
            targetKind = target.kind.ifBlank { null },
        )
        instrument.mark(SessionMarker.Kind.DWELL_START, target.label.ifBlank { target.value }, stepId = target.value)
    }

    override suspend fun holdEnded(target: LiveWidget.Hold.Target, seconds: Int) = instrument.mark(
        kind = SessionMarker.Kind.DWELL_END,
        label = target.label.ifBlank { target.value },
        stepId = target.value,
        payload = "{\"dwell_seconds\":$seconds}",
    )
}
