package sk.martinvanco.monad.quests.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QuestCompleteResponseDto(
    val success: Boolean,
    @SerialName("enrollment_id") val enrollmentId: String,
    @SerialName("points_earned") val pointsEarned: Float,
    @SerialName("completed_at") val completedAt: String,
    val status: String? = null // completed, failed, expired
)
