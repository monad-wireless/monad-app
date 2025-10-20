package sk.martinvanco.monad.home.presentation

sealed interface HomeEvent {
    data class OpenQuestDetailScreen(val questId: Number) : HomeEvent
}
