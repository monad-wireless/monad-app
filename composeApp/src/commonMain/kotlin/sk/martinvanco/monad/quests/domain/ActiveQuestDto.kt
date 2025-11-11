package sk.martinvanco.monad.quests.domain

data class ActiveTaskDto(
    val name: String,
    val instruction: String,
    val type: TaskType,
    val status: TaskStatus
)

enum class TaskStatus {
    SCHEDULED,
    ACTIVE,
    COMPLETED
}

data class ActiveQuestDto(
    val id: String,
    val name: String,
    val description: String,
    val tasks: List<ActiveTaskDto>,
    val points: Float
)
