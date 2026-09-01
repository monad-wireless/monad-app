package sk.martinvanco.monad.my_account.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.domain.AuthManager
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.profile.presentation.ProfileScreen

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
            MyAccountEvent.ViewProfileClick -> navigationManager.navigateTo(ProfileScreen())
            MyAccountEvent.LogoutClick -> logout()
            MyAccountEvent.DeleteAccountClick -> {
                mutableState.value = state.value.copy(showDeleteDialog = true, deleteError = null)
            }
            MyAccountEvent.ConfirmDeleteAccount -> deleteAccount()
            MyAccountEvent.DismissDeleteDialog -> {
                mutableState.value = state.value.copy(showDeleteDialog = false, deleteError = null)
            }
        }
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
