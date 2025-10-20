package sk.martinvanco.monad.auth.presentation.splash

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.home.presentation.HomeScreen

class SplashScreenModel(
    private val navigationManager: NavigationManager
) : StateScreenModel<SplashState>(SplashState()) {

    fun checkAuthStatus() {
        screenModelScope.launch {
            // Simulate authentication check - replace with real auth logic
            delay(1000) // Loading simulation

            // TODO: Replace with actual authentication check
            // For now, we'll assume user is not authenticated
            val isAuthenticated = true // Change to true if user has valid token/session

            mutableState.value = state.value.copy(
                isAuthChecked = true,
                isAuthenticated = isAuthenticated
            )

            // Navigate based on authentication status
            delay(500) // Small delay for UX
            if (isAuthenticated) {
                navigationManager.replace(HomeScreen())
            } else {
                navigationManager.replace(LoginScreen())
            }
        }
    }
}
