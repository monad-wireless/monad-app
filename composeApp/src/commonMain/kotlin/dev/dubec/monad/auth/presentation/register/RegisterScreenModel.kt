package dev.dubec.monad.auth.presentation.register

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import dev.dubec.monad.auth.data.api.AuthService
import dev.dubec.monad.auth.domain.AuthManager
import dev.dubec.monad.core.autofill.CredentialSave
import dev.dubec.monad.core.navigation.NavigationManager
import dev.dubec.monad.core.util.EmailValidator
import dev.dubec.monad.main.presentation.MainContainerScreen

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
            is RegisterEvent.OnTermsAcceptedChange -> {
                mutableState.value = state.value.copy(
                    termsAccepted = event.value,
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

        // The server's rule (AuthController::assertPasswordPolicy), checked here first so the
        // common mistake costs no round trip.
        if (password.length < MIN_PASSWORD_LENGTH) {
            mutableState.value = state.value.copy(passwordError = "Password must have at least $MIN_PASSWORD_LENGTH characters")
            return
        }

        if (password != repeatPassword) {
            mutableState.value = state.value.copy(repeatPasswordError = "Passwords do not match")
            return
        }

        if (!state.value.termsAccepted) {
            mutableState.value = state.value.copy(error = "You must accept the Terms and Privacy Policy")
            return
        }

        screenModelScope.launch {
            mutableState.value = state.value.copy(isLoading = true, error = null)

            try {
                val response = authService.register(email, password, name)
                // The account now exists on the server either way, so the manager may save the
                // new pair: a pending account signs in with exactly this pair once approved.
                CredentialSave.request()
                val token = response.token
                if (token == null) {
                    // Not invited from the onboarding desk: the account waits for approval and
                    // there is no session to start. Nothing is saved on the handset.
                    mutableState.value = state.value.copy(isLoading = false, pendingApproval = true)
                    return@launch
                }
                authManager.saveUserFromRegister(
                    email = response.email,
                    name = response.name,
                    token = token
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

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
