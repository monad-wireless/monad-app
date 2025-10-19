package sk.martinvanco.blarp.home_screen.presentation

data class HomeScreenState(
    val items: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
