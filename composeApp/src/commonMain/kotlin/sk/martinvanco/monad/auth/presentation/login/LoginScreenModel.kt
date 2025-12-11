package sk.martinvanco.monad.auth.presentation.login

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import sk.martinvanco.monad.auth.data.api.AuthService
import sk.martinvanco.monad.auth.domain.AuthManager
import sk.martinvanco.monad.core.data.dto.ErrorResponseDto
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.main.presentation.MainContainerScreen

class LoginScreenModel(
    private val navigationManager: NavigationManager,
    private val authManager: AuthManager,
    private val authService: AuthService
) : StateScreenModel<LoginState>(LoginState()) {

    companion object {
        private const val EMAIL_DOMAIN = "@monad.sk"
        private const val DEFAULT_PASSWORD = "password123"
    }

    fun onEvent(event: LoginEvent) {
        when (event) {
            LoginEvent.ContinueButtonClick -> {
                continueWithNickname()
            }
            is LoginEvent.OnNicknameFieldChange -> {
                mutableState.value = state.value.copy(
                    nickname = event.value,
                    nicknameError = null,
                    error = null
                )
            }
        }
    }

    private fun continueWithNickname() {
        val nickname = state.value.nickname.trim()

        if (nickname.isBlank()) {
            mutableState.value = state.value.copy(nicknameError = "Nickname is required")
            return
        }

        if (nickname.length < 2) {
            mutableState.value = state.value.copy(nicknameError = "Nickname must be at least 2 characters")
            return
        }

        val email = "$nickname$EMAIL_DOMAIN"
        val password = DEFAULT_PASSWORD

        screenModelScope.launch {
            mutableState.value = state.value.copy(isLoading = true, error = null)

            try {
                // Register with: name = nickname, email = nickname@monad.sk, password = password123
                val registerResponse = authService.register(email, password, nickname)
                authManager.saveUserFromRegister(
                    email = registerResponse.email,
                    name = registerResponse.name,
                    token = registerResponse.token
                )
                mutableState.value = state.value.copy(isLoading = false)
                navigationManager.replaceAll(MainContainerScreen())
            } catch (e: ClientRequestException) {
                val errorResponse = parseErrorResponse(e)
                val errorMessage = if (errorResponse?.code == "AUTH_007") {
                    "This nickname is already taken. Please choose another one."
                } else {
                    errorResponse?.message ?: "Failed to continue. Please try again."
                }
                mutableState.value = state.value.copy(
                    isLoading = false,
                    error = errorMessage
                )
            } catch (e: Exception) {
                mutableState.value = state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to continue. Please try again."
                )
            }
        }
    }

    private suspend fun parseErrorResponse(exception: ClientRequestException): ErrorResponseDto? {
        return try {
            val body = exception.response.bodyAsText()
            Json { ignoreUnknownKeys = true }.decodeFromString<ErrorResponseDto>(body)
        } catch (e: Exception) {
            null
        }
    }
}
