package sk.martinvanco.monad

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import com.mmk.kmpnotifier.extensions.onCreateOrOnNewIntent
import com.mmk.kmpnotifier.notification.NotifierManager
import sk.martinvanco.monad.core.deeplink.PendingDeepLink
import sk.martinvanco.monad.core.util.ContextProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextProvider.init(application)
        ContextProvider.setActivity(this)
        NotifierManager.onCreateOrOnNewIntent(intent)
        // IP-128 — park a device-label link before setContent. It cannot be
        // routed yet: Koin starts inside App()'s remember block, and the
        // NavigationManager's SharedFlow has replay = 0 with its only collector
        // inside the Navigator, so anything emitted now is dropped. The UI drains
        // this once it is ready. Must sit alongside (never replace) the
        // NotifierManager call above, which handles push-notification taps.
        PendingDeepLink.parkUrl(intent?.dataString)
        enableEdgeToEdge()

        setContent {
            // Remove when https://issuetracker.google.com/issues/364713509 is fixed
            LaunchedEffect(isSystemInDarkTheme()) {
                enableEdgeToEdge()
            }
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        NotifierManager.onCreateOrOnNewIntent(intent)
        // Warm path: the app was already running when a sticker was scanned.
        // Reachable only because the manifest declares launchMode="singleTask".
        PendingDeepLink.parkUrl(intent.dataString)
    }
}
