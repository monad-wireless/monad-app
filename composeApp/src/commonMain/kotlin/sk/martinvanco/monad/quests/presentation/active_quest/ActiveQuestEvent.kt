package sk.martinvanco.monad.quests.presentation.active_quest

sealed interface ActiveQuestEvent {
    /**
     * @param stepData what the step OBSERVED, as JSON, or null when it observed nothing.
     *   A probe puts the surveyed point it consumed here (`{"targets": ["MONAD-FP-07"]}`), which
     *   is the only record of WHERE a dwell happened that survives off this device. The backend
     *   derives the profile's coverage plan from exactly this field, so a step that completes
     *   without it produces a measurement nobody can place — and it did, for every dwell before
     *   IP-146: nothing in the app ever wrote a step's data, so `distinct_points` was always 0 and
     *   the coverage plan drew thirty-five empty points for everybody.
     */
    data class CompleteTask(val taskIndex: Int, val stepData: String? = null) : ActiveQuestEvent
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
