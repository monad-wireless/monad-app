package sk.martinvanco.monad.quests.domain

/**
 * Represents a quest that users can complete
 */
data class Quest(
    val id: String,
    val title: String,
    val description: String,
    val reward: Int, // Points or coins
    val difficulty: QuestDifficulty,
    val category: QuestCategory,
    val progress: Int = 0, // Current progress (0-100)
    val isCompleted: Boolean = false
)

enum class QuestDifficulty {
    EASY,
    MEDIUM,
    HARD
}

enum class QuestCategory {
    DAILY,
    WEEKLY,
    SPECIAL,
    CHALLENGE
}
