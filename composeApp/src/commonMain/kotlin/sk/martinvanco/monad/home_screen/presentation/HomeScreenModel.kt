package sk.martinvanco.monad.home_screen.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeScreenModel : StateScreenModel<HomeScreenState>(HomeScreenState()) {

    init {
        loadItems()
    }

    fun onEvent(event: HomeScreenEvent) {
        when (event) {
            is HomeScreenEvent.OnItemClick -> {
                // Navigation will be handled in the screen itself
            }
            HomeScreenEvent.LoadItems -> {
                loadItems()
            }
        }
    }

    private fun loadItems() {
        screenModelScope.launch {
            mutableState.value = state.value.copy(isLoading = true)

            // Simulate network call
            delay(1000)

            val items = listOf(
                "Item 1",
                "Item 2",
                "Item 3",
                "Item 4",
                "Item 5"
            )

            mutableState.value = state.value.copy(
                items = items,
                isLoading = false,
                error = null
            )
        }
    }
}
