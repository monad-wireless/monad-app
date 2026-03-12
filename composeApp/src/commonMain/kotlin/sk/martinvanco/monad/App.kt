package sk.martinvanco.monad

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.setSingletonImageLoaderFactory
import sk.martinvanco.monad.core.image.createImageLoader
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatform.getKoin
import sk.martinvanco.monad.auth.presentation.splash.SplashScreen
import sk.martinvanco.monad.core.di.appModule
import sk.martinvanco.monad.core.di.platformModule
import sk.martinvanco.monad.core.navigation.CustomScreenTransition
import sk.martinvanco.monad.core.navigation.NavigationCommand
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.navigation.NavigationManagerImpl
import sk.martinvanco.monad.core.util.Logger
import sk.martinvanco.monad.ui.theme.AppTheme
import com.mmk.kmpnotifier.notification.NotifierManager
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics

fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    startKoin {
        appDeclaration()
        modules(platformModule, appModule)
    }
}

@Composable
fun App() {
    // Initialize Logger, Koin and Firebase once
    remember<Unit> {
        try {
            Logger.init()
            initKoin()
        } catch (e: Exception) {
            // Already initialized
        }
        try {
            Firebase.crashlytics.setCrashlyticsCollectionEnabled(false)
            Logger.i("Firebase initialized, Crashlytics disabled until user accepts terms")
        } catch (e: Exception) {
            Logger.e("Firebase Crashlytics error: ${e.message}", throwable = e)
        }
        Logger.i("Initializing push notification listener...")
        NotifierManager.addListener(object : NotifierManager.Listener {
            override fun onNewToken(token: String) {
                Logger.i("Push notifications ready - FCM Token: $token")
            }

            override fun onPushNotification(title: String?, body: String?) {
                Logger.i("Push notification received - Title: $title, Body: $body")
            }
        })
        Logger.i("Push notification listener registered successfully")
    }

    // Fetch FCM token on startup
    LaunchedEffect(Unit) {
        try {
            val token = NotifierManager.getPushNotifier().getToken()
            Logger.i("FCM Token fetched: $token")
        } catch (e: Exception) {
            Logger.e("Failed to fetch FCM token: ${e.message}", throwable = e)
        }
    }

    // Setup Coil ImageLoader with platform-specific networking
    setSingletonImageLoaderFactory { context ->
        createImageLoader(context)
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
