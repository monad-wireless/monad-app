package sk.martinvanco.monad.auth.presentation.login

sealed interface LoginEvent {
    data object LoginButtonClick : LoginEvent
    data object CreateAccountButtonClick : LoginEvent
    data object ForgotPasswordClick : LoginEvent
    data class OnEmailFieldChange(val value: String) : LoginEvent
    data class OnPasswordFieldChange(val value: String) : LoginEvent
}
