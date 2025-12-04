package sk.martinvanco.monad.auth.presentation.splash

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.domain.AuthManager
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.main.presentation.MainContainerScreen

class SplashScreenModel(
    private val navigationManager: NavigationManager,
    private val authManager: AuthManager
) : StateScreenModel<SplashState>(SplashState()) {

    fun checkAuthStatus() {
        screenModelScope.launch {
            val user = authManager.getCurrentUser()
            if (user == null) {
                navigationManager.replace(LoginScreen())
                return@launch
            }

            val token = user.token
            if (token == null) {
                authManager.clearUser()
                navigationManager.replace(LoginScreen())
                return@launch
            }

            val isValid = authManager.validateToken(token)
            if (isValid) {
                navigationManager.replace(MainContainerScreen())
            } else {
                authManager.clearUser()
                navigationManager.replace(LoginScreen())
            }
        }
    }
}
