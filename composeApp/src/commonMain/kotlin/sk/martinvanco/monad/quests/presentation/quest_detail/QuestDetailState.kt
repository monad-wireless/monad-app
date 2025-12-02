package sk.martinvanco.monad.quests.presentation.quest_detail

import sk.martinvanco.monad.quests.domain.QuestDetailDto

data class QuestDetailState(
    val questId: String = "",
    val quest: QuestDetailDto? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isStartingQuest: Boolean = false,
    val startQuestError: String? = null,
    val enrollmentId: String? = null
)
