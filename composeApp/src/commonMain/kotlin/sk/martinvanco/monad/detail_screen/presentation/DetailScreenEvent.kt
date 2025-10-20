package sk.martinvanco.monad.detail_screen.presentation

sealed interface DetailScreenEvent {
    data object NavigateBack : DetailScreenEvent
    data object LoadDetails : DetailScreenEvent
}
