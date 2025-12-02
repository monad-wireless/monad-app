package sk.martinvanco.monad.quests.presentation.active_quest

sealed interface ActiveQuestEvent {
    data class CompleteTask(val taskIndex: Int) : ActiveQuestEvent
    data class FailTask(val taskIndex: Int, val reason: String) : ActiveQuestEvent
    data object EndQuestEarly : ActiveQuestEvent
    data object SubmitQuest : ActiveQuestEvent  // Final submission with upload
    data object RetryUpload : ActiveQuestEvent
}
