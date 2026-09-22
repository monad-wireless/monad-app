package sk.martinvanco.monad.quests.data.dto

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
    val config: JsonElement? = null, // Dynamic config based on task type
    /**
     * The backend's completion row for this step, minted at quest start (IP-162). A v3 sweep event
     * carries it verbatim, so the evidence names the step it belongs to rather than an ordinal.
     * Null only for a task built without a step row, which no quest screen does.
     */
    @SerialName("step_completion_id") val stepCompletionId: String? = null,
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
