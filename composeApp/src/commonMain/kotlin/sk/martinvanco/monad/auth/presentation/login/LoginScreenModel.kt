package sk.martinvanco.monad.auth.presentation.login

import cafe.adriel.voyager.core.model.StateScreenModel
import sk.martinvanco.monad.auth.presentation.register.RegisterScreen
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.util.Logger

class LoginScreenModel(
    private val navigationManager: NavigationManager
) : StateScreenModel<LoginState>(LoginState()) {
    fun onEvent(event: LoginEvent) {
        Logger.d("Login event received: $event", tag = "LoginScreen")

        when(event) {
            is LoginEvent.CreateAccountButtonClick -> {
                Logger.i("Navigate to create account", tag = "LoginScreen")
                navigationManager.navigateTo(RegisterScreen())
            }
            LoginEvent.ForgotPasswordClick -> {
                Logger.i("Navigate to forgot password", tag = "LoginScreen")
                // TODO: Navigate to forgot password screen
            }
            LoginEvent.LoginButtonClick -> {
                Logger.i("Login button clicked", tag = "LoginScreen")
                // TODO: Implement login logic
                // Example: After successful login, navigate to main screen
                // navigationManager.replace(MainContainerScreen())
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
