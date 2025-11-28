package sk.martinvanco.monad.home.domain.model

import sk.martinvanco.monad.home.data.dto.QuestDto

data class QuestCardDt(
    val id: String,
    val name: String,
    val description: String,
    val numTasks: Int,
    val timeEstimateMin: Int?,
    val points: Float,
) {
    companion object {
        fun fromDto(dto: QuestDto): QuestCardDt {
            return QuestCardDt(
                id = dto.id,
                name = dto.name,
                description = dto.description,
                numTasks = dto.numberOfSteps,
                timeEstimateMin = dto.estimatedDuration,
                points = dto.points
            )
        }
    }
}