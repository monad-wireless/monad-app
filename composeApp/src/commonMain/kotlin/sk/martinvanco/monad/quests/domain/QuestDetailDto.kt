package sk.martinvanco.monad.quests.domain

/**
 * Represents a task within a quest
 */
data class TaskDto(
    val name: String,
    val instruction: String,
    val type: TaskType
)

/**
 * Task types that define what action the user needs to perform
 */
enum class TaskType {
    START,
    STOP,
    SCAN_QR,
    CONNECT_AT,
    WAIT,
    SUBMIT
}

/**
 * Detailed quest information including all tasks
 */
data class QuestDetailDto(
    val id: String, // UUID
    val name: String,
    val description: String,
    val duration: Int, // in minutes
    val tasks: List<TaskDto>,
    val points: Float,
    val questType: String // e.g., "Scan & Walk", "Network", etc.
)
