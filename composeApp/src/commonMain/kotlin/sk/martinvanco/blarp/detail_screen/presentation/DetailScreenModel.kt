package sk.martinvanco.blarp.detail_screen.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DetailScreenModel(
    itemName: String
) : StateScreenModel<DetailScreenState>(DetailScreenState(itemName = itemName)) {

    init {
        loadDetails()
    }

    fun onEvent(event: DetailScreenEvent) {
        when (event) {
            DetailScreenEvent.NavigateBack -> {
                // Navigation handled in screen
            }
            DetailScreenEvent.LoadDetails -> {
                loadDetails()
            }
        }
    }

    private fun loadDetails() {
        screenModelScope.launch {
            mutableState.value = state.value.copy(isLoading = true)

            // Simulate network call
            delay(1000)

            val details = """
                This is detailed information about ${state.value.itemName}.

                Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.

                Key features:
                • Feature 1
                • Feature 2
                • Feature 3
            """.trimIndent()

            mutableState.value = state.value.copy(
                details = details,
                isLoading = false,
                error = null
            )
        }
    }
}
