package sk.martinvanco.monad.auth.presentation.login

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.data.api.AuthService
import sk.martinvanco.monad.auth.domain.AuthManager
import sk.martinvanco.monad.auth.presentation.register.RegisterScreen
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.util.EmailValidator
import sk.martinvanco.monad.main.presentation.MainContainerScreen

class LoginScreenModel(
    private val navigationManager: NavigationManager,
    private val authManager: AuthManager,
    private val authService: AuthService
) : StateScreenModel<LoginState>(LoginState()) {
    fun onEvent(event: LoginEvent) {
        when(event) {
            is LoginEvent.CreateAccountButtonClick -> {
                navigationManager.navigateTo(RegisterScreen())
            }
            LoginEvent.ForgotPasswordClick -> {
                // TODO: Navigate to forgot password screen
            }
            LoginEvent.LoginButtonClick -> {
                login()
            }
            is LoginEvent.OnPasswordFieldChange -> {
                mutableState.value = state.value.copy(
                    password = event.value,
                    passwordError = null,
                    error = null
                )
            }
            is LoginEvent.OnEmailFieldChange -> {
                mutableState.value = state.value.copy(
                    email = event.value,
                    emailError = null,
                    error = null
                )
            }
        }
    }

    private fun login() {
        val email = state.value.email.trim()
        val password = state.value.password

        if (!EmailValidator.isValid(email)) {
            mutableState.value = state.value.copy(emailError = "Invalid email address")
            return
        }

        if (password.isBlank()) {
            mutableState.value = state.value.copy(passwordError = "Password is required")
            return
        }

        screenModelScope.launch {
            mutableState.value = state.value.copy(isLoading = true, error = null)

            try {
                val response = authService.login(email, password)
                authManager.saveUserFromLogin(response.email, response.name, response.token)
                mutableState.value = state.value.copy(isLoading = false)
                navigationManager.replaceAll(MainContainerScreen())
            } catch (e: Exception) {
                val errorMessage = parseErrorMessage(e)
                mutableState.value = state.value.copy(isLoading = false, error = errorMessage)
            }
        }
    }

    private fun parseErrorMessage(exception: Exception): String {
        val message = exception.message ?: return "Login failed. Please try again"

        val jsonStart = message.indexOf("{\"")
        if (jsonStart != -1) {
            try {
                val jsonPart = message.substring(jsonStart)
                val messageMatch = "\"message\":\"([^\"]+)\"".toRegex().find(jsonPart)
                if (messageMatch != null) {
                    return messageMatch.groupValues[1]
                }
            } catch (e: Exception) {
                // Fall through to default
            }
        }

        return message
    }
}
