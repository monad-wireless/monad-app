package sk.martinvanco.blarp.auth.presentation.login

sealed interface LoginEvent {
    data object OnLoginButtonClick : LoginEvent
    data object OnCreateAccountButtonClick : LoginEvent
    data object OnForgotPasswordClick : LoginEvent
    data class OnEmailFieldChange(val value: String) : LoginEvent
    data class OnPasswordFieldChange(val value: String) : LoginEvent

}
