package sk.martinvanco.monad.notifications.domain

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.mmk.kmpnotifier.permission.AndroidPermissionUtil
import io.github.aakira.napier.Napier
import kotlinx.coroutines.suspendCancellableCoroutine
import sk.martinvanco.monad.core.util.ContextProvider
import kotlin.coroutines.resume

/**
 * Android actual.
 *
 * `POST_NOTIFICATIONS` is a runtime permission from API 33. Below that the OS grants it with the
 * install and the only off switch is the app's notification page in system settings, so the status
 * is always determined there. From 33 up, a missing grant is indistinguishable from "never asked",
 * which is why [OsNotificationStatus.determined] is null and the app's own flag decides.
 *
 * The request needs an `ActivityResultLauncher` registered before the Activity is started, which
 * is why `MainActivity.onCreate` builds kmpNotifier's `AndroidPermissionUtil` and hands it to
 * [NotificationPermissionBridge]. Without an Activity the request degrades to the current state.
 */
actual class NotificationPermission actual constructor() {

    actual suspend fun status(): OsNotificationStatus {
        val enabled = NotificationManagerCompat.from(ContextProvider.getContext()).areNotificationsEnabled()
        val runtimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        return OsNotificationStatus(granted = enabled, determined = if (runtimePermission) null else true)
    }

    actual suspend fun request(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return status().granted
        return NotificationPermissionBridge.request() ?: status().granted
    }

    actual fun openSystemSettings() {
        val context = ContextProvider.getContext()
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { (ContextProvider.getActivity() ?: context).startActivity(intent) }
            .onFailure { Napier.w("[push] could not open notification settings: ${it.message}") }
    }
}

/**
 * Holds the Activity-bound permission launcher for the process.
 *
 * kmpNotifier 1.6.1's `NotifierManager.getPermissionUtil()` is a check-only mock on Android; the
 * real asker is `ComponentActivity.permissionUtil()`, which must be created in `onCreate`. The
 * Activity registers it here; the actual above reads it when a screen asks.
 */
object NotificationPermissionBridge {
    @Volatile
    private var util: AndroidPermissionUtil? = null

    fun attach(util: AndroidPermissionUtil) {
        this.util = util
    }

    fun detach() {
        util = null
    }

    /** Null when no Activity has attached a launcher, so the caller can fall back to a status read. */
    suspend fun request(): Boolean? {
        val launcher = util ?: return null
        return suspendCancellableCoroutine { continuation ->
            launcher.askNotificationPermission { granted ->
                if (continuation.isActive) continuation.resume(granted)
            }
        }
    }
}
