package sk.martinvanco.monad

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import sk.martinvanco.monad.auth.presentation.splash.SplashScreen
import sk.martinvanco.monad.core.di.appModule
import sk.martinvanco.monad.core.util.Logger
import sk.martinvanco.monad.ui.theme.AppTheme

fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    startKoin {
        appDeclaration()
        modules(appModule)
    }
}

@Composable
fun App() {
    // Initialize Logger and Koin once
    remember {
        try {
            Logger.init()
            initKoin()
        } catch (e: Exception) {
            // Already initialized
        }
    }

    AppTheme(darkTheme = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Navigator(SplashScreen()) { navigator ->
                SlideTransition(navigator)
            }
        }
    }
}
