package sk.martinvanco.monad.quests.presentation.active_quest

import sk.martinvanco.monad.quests.domain.ActiveTaskDto

data class ActiveQuestState(
    val questId: String = "",
    val questName: String = "",
    val tasks: List<ActiveTaskDto> = emptyList(),
    val points: Float = 0f,
    val isBleCollecting: Boolean = false,
    val bleRecordCount: Long = 0
)
