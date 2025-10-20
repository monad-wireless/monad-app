package sk.martinvanco.monad.home_screen.presentation

sealed interface HomeScreenEvent {
    data class OnItemClick(val itemName: String) : HomeScreenEvent
    data object LoadItems : HomeScreenEvent
}
