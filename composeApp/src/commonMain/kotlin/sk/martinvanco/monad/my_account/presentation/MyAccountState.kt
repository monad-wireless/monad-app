package sk.martinvanco.monad.my_account.presentation

data class MyAccountState(
    val userName: String? = null,
    val userEmail: String = "",
    val isLoading: Boolean = true,
    val showDeleteDialog: Boolean = false,
    val isDeleting: Boolean = false,
    val deleteError: String? = null
)
