package sk.martinvanco.monad.my_account.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.domain.AuthManager
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.core.navigation.NavigationManager

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
        }
    }

    private fun logout() {
        screenModelScope.launch {
            authManager.clearUser()
            navigationManager.replaceAll(LoginScreen())
        }
    }
}
