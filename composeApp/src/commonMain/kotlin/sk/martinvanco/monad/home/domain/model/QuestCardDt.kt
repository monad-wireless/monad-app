package sk.martinvanco.monad.home.domain.model
import kotlinx.serialization.*


@Serializable
data class QuestCardDt (
    val id: String,
    val name: String,
    val numTasks: Int,
    val timeEstimateMin: Int,
    val points: Float,
    val questType: String,
)

data class QuestTypeDt (
    val id: String,
    val name: String,
    val icon: String,
)