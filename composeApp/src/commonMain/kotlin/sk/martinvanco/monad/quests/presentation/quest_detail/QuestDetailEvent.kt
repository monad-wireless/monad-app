package sk.martinvanco.monad.quests.presentation.quest_detail

sealed interface QuestDetailEvent {
    data object LoadQuest : QuestDetailEvent
    data object RetryLoad : QuestDetailEvent
}
