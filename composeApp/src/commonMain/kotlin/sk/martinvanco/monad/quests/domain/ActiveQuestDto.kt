package sk.martinvanco.monad.quests.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents an active task being executed in a quest
 */
@Serializable
data class ActiveTaskDto(
    val name: String,
    val description: String,
    val type: TaskType,
    val status: TaskStatus,
    val config: JsonElement? = null // Dynamic config based on task type
)

/**
 * Task execution status
 */
@Serializable
enum class TaskStatus {
    @SerialName("scheduled")
    SCHEDULED,

    @SerialName("active")
    ACTIVE,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("skipped")
    SKIPPED
}

/**
 * Active quest instance with execution state
 */
@Serializable
data class ActiveQuestDto(
    val id: String,
    val name: String,
    val description: String,
    val tasks: List<ActiveTaskDto>,
    val points: Float
)
