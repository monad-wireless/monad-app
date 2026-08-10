package sk.martinvanco.monad.quests.presentation.active_quest

import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto

data class ActiveQuestState(
    val questId: String = "",
    val enrollmentId: String = "",
    val questName: String = "",
    val userName: String = "",
    val tasks: List<ActiveTaskDto> = emptyList(),
    val points: Float = 0f,
    val isBleCollecting: Boolean = false,
    val bleRecordCount: Long = 0L,
    val isLoading: Boolean = true,
    /** Fatal: the quest itself could not be loaded. Replaces the whole screen. */
    val error: String? = null,
    /**
     * Non-fatal: the radio instrument did not start.
     *
     * Deliberately a separate field from [error]. The two used to share one, so an instrument
     * abort — an AP being out of range, say — blanked the entire step list and left the
     * participant with Go Back / Retry and no way to continue, even though the quest is perfectly
     * runnable without the radio and the coordinator's own contract says the failure is not fatal.
     * It is a dismissible banner now, and the steps stay on screen.
     */
    val instrumentWarning: String? = null,
    // Quest completion fields
    val allTasksCompleted: Boolean = false,
    val isUploading: Boolean = false,
    val uploadProgress: String = "",
    val isCompleted: Boolean = false,
    val completionError: String? = null,
    val startTime: Long = 0L,
    val shouldNavigateHome: Boolean = false,
    // End quest early fields
    val showEndQuestConfirmation: Boolean = false,
    val navigateToEndedEarlyScreen: Boolean = false,
    // Navigate to completed screen
    val navigateToCompletedScreen: Boolean = false
)
