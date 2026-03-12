package sk.martinvanco.monad.auth.presentation.register

sealed interface RegisterEvent {
    data object CreateAccountButtonClick : RegisterEvent
    data object LoginButtonClick : RegisterEvent
    data class OnNameFieldChange(val value: String) : RegisterEvent
    data class OnEmailFieldChange(val value: String) : RegisterEvent
    data class OnPasswordFieldChange(val value: String) : RegisterEvent
    data class OnRepeatPasswordFieldChange(val value: String) : RegisterEvent
    data class OnTermsAcceptedChange(val value: Boolean) : RegisterEvent
}
