package sk.martinvanco.monad.auth.presentation.register

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.data.api.AuthService
import sk.martinvanco.monad.auth.domain.AuthManager
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.util.EmailValidator
import sk.martinvanco.monad.main.presentation.MainContainerScreen

class RegisterScreenModel(
    private val navigationManager: NavigationManager,
    private val authManager: AuthManager,
    private val authService: AuthService
) : StateScreenModel<RegisterState>(RegisterState()) {

    fun onEvent(event: RegisterEvent) {
        when(event) {
            RegisterEvent.CreateAccountButtonClick -> {
                register()
            }
            is RegisterEvent.LoginButtonClick -> {
                navigationManager.navigateBack()
            }
            is RegisterEvent.OnNameFieldChange -> {
                mutableState.value = state.value.copy(name = event.value)
            }
            is RegisterEvent.OnEmailFieldChange -> {
                mutableState.value = state.value.copy(
                    email = event.value,
                    emailError = null,
                    error = null
                )
            }
            is RegisterEvent.OnPasswordFieldChange -> {
                mutableState.value = state.value.copy(
                    password = event.value,
                    passwordError = null,
                    error = null
                )
            }
            is RegisterEvent.OnRepeatPasswordFieldChange -> {
                mutableState.value = state.value.copy(
                    repeatPassword = event.value,
                    repeatPasswordError = null,
                    error = null
                )
            }
        }
    }

    private fun register() {
        val email = state.value.email.trim()
        val password = state.value.password
        val repeatPassword = state.value.repeatPassword
        val name = state.value.name.trim().ifBlank { null }

        if (!EmailValidator.isValid(email)) {
            mutableState.value = state.value.copy(emailError = "Invalid email address")
            return
        }

        if (password.isBlank()) {
            mutableState.value = state.value.copy(passwordError = "Password is required")
            return
        }

        if (password != repeatPassword) {
            mutableState.value = state.value.copy(repeatPasswordError = "Passwords do not match")
            return
        }

        screenModelScope.launch {
            mutableState.value = state.value.copy(isLoading = true, error = null)

            try {
                val response = authService.register(email, password, name)
                authManager.saveUserFromRegister(
                    email = response.email,
                    name = response.name,
                    token = response.token
                )
                mutableState.value = state.value.copy(isLoading = false)
                navigationManager.replaceAll(MainContainerScreen())
            } catch (e: Exception) {
                val errorMessage = parseErrorMessage(e)
                mutableState.value = state.value.copy(isLoading = false, error = errorMessage)
            }
        }
    }

    private fun parseErrorMessage(exception: Exception): String {
        val message = exception.message ?: return "Registration failed. Please try again"

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
