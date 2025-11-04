package sk.martinvanco.monad.auth.presentation.splash

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.main.presentation.MainContainerScreen
import sk.martinvanco.monad.quests.presentation.QuestDetailScreen

class SplashScreenModel(
    private val navigationManager: NavigationManager
) : StateScreenModel<SplashState>(SplashState()) {

    fun checkAuthStatus() {
        screenModelScope.launch {
            // Simulate authentication check - replace with real auth logic

            // TODO: Replace with actual authentication check
            // For now, we'll assume user is not authenticated
            val isAuthenticated = true // Change to true if user has valid token/session

            mutableState.value = state.value.copy(
                isAuthChecked = true,
                isAuthenticated = isAuthenticated
            )

            // Navigate based on authentication status
            // TODO: TEMPORARY - Navigate to Quest Detail for testing
            navigationManager.replace(QuestDetailScreen("test-quest-id-123"))

            // Original navigation logic (commented for testing):
            // if (isAuthenticated) {
            //     navigationManager.replace(MainContainerScreen())
            // } else {
            //     navigationManager.replace(LoginScreen())
            // }
        }
    }
}
