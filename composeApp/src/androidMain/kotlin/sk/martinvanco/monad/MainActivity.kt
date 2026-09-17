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
import com.mmk.kmpnotifier.permission.permissionUtil
import sk.martinvanco.monad.core.deeplink.PendingDeepLink
import sk.martinvanco.monad.core.util.ContextProvider
import sk.martinvanco.monad.notifications.domain.NotificationPermissionBridge
import sk.martinvanco.monad.notifications.domain.PendingPushRoute

class MainActivity : ComponentActivity() {

    // IP-157 — the only object that can put the POST_NOTIFICATIONS dialog on screen. It registers
    // an ActivityResultLauncher, which is legal only before the Activity is started, so it is a
    // field initialised here and handed to the shared code through the bridge in onCreate.
    private val notificationPermission by permissionUtil()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextProvider.init(application)
        ContextProvider.setActivity(this)
        NotificationPermissionBridge.attach(notificationPermission)
        NotifierManager.onCreateOrOnNewIntent(intent)
        // IP-128 — park a device-label link before setContent. It cannot be
        // routed yet: Koin starts inside App()'s remember block, and the
        // NavigationManager's SharedFlow has replay = 0 with its only collector
        // inside the Navigator, so anything emitted now is dropped. The UI drains
        // this once it is ready. Must sit alongside (never replace) the
        // NotifierManager call above, which handles push-notification taps.
        PendingDeepLink.parkUrl(intent?.dataString)
        // IP-157 — and a tapped push, for the same reason. kmpNotifier's listener is installed in
        // App()'s remember block, so on a cold start `onCreateOrOnNewIntent` above finds nobody to
        // tell; the payload rides as intent extras (both the FCM tray and the library put it
        // there), so it is read here and parked. Parking the same route twice is harmless.
        parkPushRoute(intent)
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
        parkPushRoute(intent)
    }

    override fun onDestroy() {
        NotificationPermissionBridge.detach()
        super.onDestroy()
    }

    private fun parkPushRoute(intent: Intent?) {
        val extras = intent?.extras ?: return
        val payload = extras.keySet().associateWith { key -> @Suppress("DEPRECATION") extras.get(key) }
        PendingPushRoute.parkPayload(payload)
    }
}
