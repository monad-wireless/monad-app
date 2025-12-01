package sk.martinvanco.monad.quests.presentation.active_quest

sealed interface ActiveQuestEvent {
    data class CompleteTask(val taskIndex: Int) : ActiveQuestEvent
    data class ReportIssue(val taskIndex: Int) : ActiveQuestEvent
    data object EndQuest : ActiveQuestEvent
}
