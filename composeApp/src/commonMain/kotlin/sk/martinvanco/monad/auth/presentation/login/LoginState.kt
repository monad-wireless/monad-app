package sk.martinvanco.monad.auth.presentation.login

data class LoginState(
    val email: String = "email@monad.sk",
    val password: String = "test123",
    val isLoading: Boolean = false
)
