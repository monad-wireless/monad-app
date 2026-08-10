package sk.martinvanco.monad.quests.presentation.active_quest

sealed interface ActiveQuestEvent {
    data class CompleteTask(val taskIndex: Int) : ActiveQuestEvent
    data class FailTask(val taskIndex: Int, val reason: String) : ActiveQuestEvent
    data object SubmitQuest : ActiveQuestEvent
    data object RetryUpload : ActiveQuestEvent
    data object RetryLoad : ActiveQuestEvent
    data object DismissInstrumentWarning : ActiveQuestEvent
    data object RetryInstrument : ActiveQuestEvent
    data object DismissCompletionError : ActiveQuestEvent
    data object DismissSuccessAndNavigateHome : ActiveQuestEvent
    // End quest early events
    data object ShowEndQuestConfirmation : ActiveQuestEvent
    data object DismissEndQuestConfirmation : ActiveQuestEvent
    data object ConfirmEndQuest : ActiveQuestEvent
}
