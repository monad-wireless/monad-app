package sk.martinvanco.monad.auth.presentation.register

data class RegisterState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val repeatPassword: String = "",
    val isLoading: Boolean = false
)
