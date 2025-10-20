package sk.martinvanco.blarp.auth.presentation.splash

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashScreenModel : StateScreenModel<SplashState>(SplashState()) {

    fun checkAuthStatus() {
        screenModelScope.launch {
            // Simulate authentication check - replace with real auth logic
            delay(2000) // 2 seconds loading simulation

            // TODO: Replace with actual authentication check
            // For now, we'll assume user is not authenticated
            mutableState.value = state.value.copy(
                isAuthChecked = true,
                isAuthenticated = false // Change to true if user has valid token/session
            )
        }
    }
}
