package sk.martinvanco.monad.auth.presentation.login

import cafe.adriel.voyager.core.model.StateScreenModel
import sk.martinvanco.monad.core.util.Logger

class LoginScreenModel : StateScreenModel<LoginState>(LoginState()) {
    fun onEvent(event: LoginEvent) {
        Logger.d("Login event received: $event", tag = "LoginScreen")

        when(event) {
            LoginEvent.OnCreateAccountButtonClick -> {
                Logger.i("Navigate to create account", tag = "LoginScreen")
            }
            LoginEvent.OnForgotPasswordClick -> {
                Logger.i("Navigate to forgot password", tag = "LoginScreen")
            }
            LoginEvent.OnLoginButtonClick -> {
                Logger.i("Login button clicked", tag = "LoginScreen")
                // TODO: Implement login logic
            }
            is LoginEvent.OnPasswordFieldChange -> {
                Logger.d("Password field changed (length: ${event.value.length})", tag = "LoginScreen")
                mutableState.value = state.value.copy(password = event.value)
            }
            is LoginEvent.OnEmailFieldChange -> {
                Logger.d("Email field changed to: ${event.value}", tag = "LoginScreen")
                mutableState.value = state.value.copy(email = event.value)
            }
        }
    }
}
