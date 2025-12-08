package sk.martinvanco.monad

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.Navigator
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatform.getKoin
import sk.martinvanco.monad.auth.presentation.splash.SplashScreen
import sk.martinvanco.monad.core.di.appModule
import sk.martinvanco.monad.core.navigation.CustomScreenTransition
import sk.martinvanco.monad.core.navigation.NavigationCommand
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.navigation.NavigationManagerImpl
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
                // Get NavigationManager from Koin and observe navigation commands
                val navigationManager = remember {
                    getKoin().get<NavigationManager>() as NavigationManagerImpl
                }

                LaunchedEffect(Unit) {
                    navigationManager.navigationCommands.collect { command ->
                        when (command) {
                            is NavigationCommand.Navigate -> navigator.push(command.screen)
                            is NavigationCommand.Back -> navigator.pop()
                            is NavigationCommand.Replace -> navigator.replace(command.screen)
                            is NavigationCommand.ReplaceAll -> navigator.replaceAll(command.screen)
                        }
                    }
                }

                CustomScreenTransition(navigator)
            }
        }
    }
}
