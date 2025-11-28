package sk.martinvanco.monad.home.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class QuestListResponseDto(
    val quests: List<QuestDto>
)

@Serializable
data class QuestDto(
    val id: String,
    val name: String,
    val description: String,
    val points: Float,
    val estimatedDuration: Int?,
    val numberOfSteps: Int
)
