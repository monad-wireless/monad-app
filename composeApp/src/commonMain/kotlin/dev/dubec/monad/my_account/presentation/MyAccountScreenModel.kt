package dev.dubec.monad.my_account.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import dev.dubec.monad.auth.domain.AuthManager
import dev.dubec.monad.auth.presentation.login.LoginScreen
import dev.dubec.monad.core.autofill.CredentialSave
import dev.dubec.monad.core.navigation.NavigationManager

class MyAccountScreenModel(
    private val authManager: AuthManager,
    private val navigationManager: NavigationManager
) : StateScreenModel<MyAccountState>(MyAccountState()) {

    init {
        loadUserData()
    }

    private fun loadUserData() {
        screenModelScope.launch {
            mutableState.value = state.value.copy(isLoading = true)

            val user = authManager.getCurrentUser()

            mutableState.value = state.value.copy(
                userName = user?.name,
                userEmail = user?.email ?: "",
                isLoading = false
            )
        }
    }

    fun onEvent(event: MyAccountEvent) {
        when (event) {
            MyAccountEvent.LogoutClick -> logout()
            MyAccountEvent.DeleteAccountClick -> {
                mutableState.value = state.value.copy(showDeleteDialog = true, deleteError = null)
            }
            MyAccountEvent.ConfirmDeleteAccount -> deleteAccount()
            MyAccountEvent.DismissDeleteDialog -> {
                mutableState.value = state.value.copy(showDeleteDialog = false, deleteError = null)
            }
            MyAccountEvent.ChangePasswordClick -> {
                mutableState.value = state.value.copy(
                    showChangePasswordDialog = true,
                    changePasswordError = null,
                    passwordChanged = false
                )
            }
            MyAccountEvent.DismissChangePassword -> {
                mutableState.value = state.value.copy(showChangePasswordDialog = false, changePasswordError = null)
            }
            is MyAccountEvent.ConfirmChangePassword -> changePassword(event)
        }
    }

    private fun changePassword(event: MyAccountEvent.ConfirmChangePassword) {
        val error = when {
            event.currentPassword.isEmpty() -> "Enter your current password"
            event.newPassword.length < MIN_PASSWORD_LENGTH -> "The new password must have at least $MIN_PASSWORD_LENGTH characters"
            event.newPassword != event.repeatPassword -> "The new passwords do not match"
            else -> null
        }
        if (error != null) {
            mutableState.value = state.value.copy(changePasswordError = error)
            return
        }

        screenModelScope.launch {
            mutableState.value = state.value.copy(isChangingPassword = true, changePasswordError = null)
            authManager.changePassword(event.currentPassword, event.newPassword)
                .onSuccess {
                    // The server accepted the new password, so a manager may update its entry.
                    CredentialSave.request()
                    mutableState.value = state.value.copy(
                        isChangingPassword = false,
                        showChangePasswordDialog = false,
                        passwordChanged = true
                    )
                }
                .onFailure { e ->
                    mutableState.value = state.value.copy(
                        isChangingPassword = false,
                        changePasswordError = changePasswordMessage(e)
                    )
                }
        }
    }

    /** AUTH_010 is the one refusal a participant can fix by typing again, so it gets its own words. */
    private fun changePasswordMessage(e: Throwable): String {
        val message = e.message ?: return "Could not change the password. Please try again"
        if (message.contains("AUTH_010")) return "The current password is not correct"
        return "\"message\":\"([^\"]+)\"".toRegex().find(message)?.groupValues?.get(1)
            ?: "Could not change the password. Please try again"
    }

    private companion object {
        /** The server's rule, AuthController::assertPasswordPolicy. */
        const val MIN_PASSWORD_LENGTH = 8
    }

    private fun logout() {
        screenModelScope.launch {
            authManager.clearUser()
            navigationManager.replaceAll(LoginScreen())
        }
    }

    private fun deleteAccount() {
        screenModelScope.launch {
            mutableState.value = state.value.copy(isDeleting = true, deleteError = null)
            authManager.deleteAccount()
                .onSuccess {
                    navigationManager.replaceAll(LoginScreen())
                }
                .onFailure { error ->
                    mutableState.value = state.value.copy(
                        isDeleting = false,
                        deleteError = error.message ?: "Failed to delete account"
                    )
                }
        }
    }
}
