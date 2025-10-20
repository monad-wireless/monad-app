package sk.martinvanco.monad.auth.presentation.splash

data class SplashState(
    val isAuthChecked: Boolean = false,
    val isAuthenticated: Boolean = false
)
