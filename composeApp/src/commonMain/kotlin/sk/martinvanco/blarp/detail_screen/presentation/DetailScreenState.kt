package sk.martinvanco.blarp.detail_screen.presentation

data class DetailScreenState(
    val itemName: String = "",
    val details: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)
