package sk.martinvanco.monad.my_account.presentation

data class MyAccountState(
    val userName: String? = null,
    val userEmail: String = "",
    val isLoading: Boolean = true
)
