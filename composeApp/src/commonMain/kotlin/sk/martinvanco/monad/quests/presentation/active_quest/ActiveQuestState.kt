package sk.martinvanco.monad.quests.presentation.active_quest

import sk.martinvanco.monad.quests.domain.ActiveTaskDto

data class ActiveQuestState(
    val questId: String = "",
    val enrollmentId: String = "",
    val questName: String = "",
    val tasks: List<ActiveTaskDto> = emptyList(),
    val points: Float = 0f,
    val isBleCollecting: Boolean = false,
    val bleRecordCount: Long = 0L,
    val isLoading: Boolean = true,
    val error: String? = null,
    // New fields for quest completion
    val isUploading: Boolean = false,
    val uploadProgress: String = "",  // "Uploading BLE data...", "Uploading metadata...", etc.
    val isCompleted: Boolean = false,
    val completionError: String? = null,
    val startTime: Long = 0L,
    val shouldNavigateHome: Boolean = false
)
