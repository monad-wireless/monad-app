package sk.martinvanco.blarp.detail_screen.presentation

sealed interface DetailScreenEvent {
    data object NavigateBack : DetailScreenEvent
    data object LoadDetails : DetailScreenEvent
}
