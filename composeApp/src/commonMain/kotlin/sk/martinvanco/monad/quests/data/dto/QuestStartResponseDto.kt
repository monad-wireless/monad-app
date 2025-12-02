package sk.martinvanco.monad.quests.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class QuestStartResponseDto(
    @SerialName("enrollment_id") val enrollmentId: String,
    val quest: QuestStartQuestDto,
    @SerialName("data_path") val dataPath: String,
    @SerialName("started_at") val startedAt: String
)

@Serializable
data class QuestStartQuestDto(
    val id: String,
    val name: String,
    val description: String,
    val points: Float = 0f,
    val steps: List<QuestStartStepDto>
)

@Serializable
data class QuestStartStepDto(
    @SerialName("step_id") val id: String,
    @SerialName("step_completion_id") val stepCompletionId: String,
    val name: String,
    val order: Int,
    val type: String,
    val config: JsonElement? = null
)
