package sk.martinvanco.monad.detail_screen.presentation

data class DetailScreenState(
    val itemName: String = "",
    val details: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)
