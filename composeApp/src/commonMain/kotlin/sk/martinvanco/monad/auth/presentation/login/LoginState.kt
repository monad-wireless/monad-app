package sk.martinvanco.monad.auth.presentation.login

data class LoginState(
    val nickname: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val nicknameError: String? = null
)
