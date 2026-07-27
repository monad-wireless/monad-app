package sk.martinvanco.monad.home.presentation

sealed interface HomeEvent {
    data class OpenQuestDetailScreen(val questId: Number) : HomeEvent
    data object StartBleScan : HomeEvent
    data object StopBleScan : HomeEvent
    data class UpdateFilter(val filterText: String) : HomeEvent
    data object LoadQuests : HomeEvent
    data object RetryUploads : HomeEvent
}
