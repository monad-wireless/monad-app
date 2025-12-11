package sk.martinvanco.monad.auth.presentation.login

sealed interface LoginEvent {
    data object ContinueButtonClick : LoginEvent
    data class OnNicknameFieldChange(val value: String) : LoginEvent
}
