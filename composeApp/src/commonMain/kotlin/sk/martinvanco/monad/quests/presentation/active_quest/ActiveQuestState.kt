package sk.martinvanco.monad.quests.presentation.active_quest

import sk.martinvanco.monad.quests.domain.ActiveTaskDto

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
    val error: String? = null,
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
    val navigateToEndedEarlyScreen: Boolean = false
)
