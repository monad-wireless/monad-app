package dev.dubec.monad.quests.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The wire of `POST /api/quest/live/{stepCompletionId}`, protocol `monad-live/v1`. */
@Serializable
data class LiveSyncRequestDto(
    val events: List<LiveEventDto>,
    /** The service UUID this phone advertises; null while nothing is on air. */
    val subject: String?,
    val broadcasting: Boolean,
)

@Serializable
data class LiveEventDto(
    val id: String,
    val kind: String,
    val data: JsonObject,
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("mono_ns") val monoNs: Long,
)

@Serializable
data class LiveSyncResponseDto(
    val protocol: String,
    val phase: String = "",
    val view: LiveViewDto,
    @SerialName("poll_ms") val pollMs: Long = 1_000,
    val done: Boolean = false,
    val result: JsonElement? = null,
    val acked: List<String> = emptyList(),
)

/** Widgets stay raw here: an unknown type must not fail the screen it stands on. */
@Serializable
data class LiveViewDto(
    val title: String = "",
    val widgets: List<JsonObject> = emptyList(),
)
