package sk.martinvanco.monad.notifications.domain

import com.mmk.kmpnotifier.notification.NotifierManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * iOS actual.
 *
 * `UNUserNotificationCenter` reports `notDetermined` as a real status, so both halves of
 * [OsNotificationStatus] are answered here. The request goes through kmpNotifier 1.6.1's
 * `NotifierManager.getPermissionUtil()` (`IosPermissionUtil`, alert + sound + badge), which is the
 * same call the library used to make on start before `askNotificationPermissionOnStart` was
 * turned off in `iOSApp.swift`.
 */
actual class NotificationPermission actual constructor() {

    actual suspend fun status(): OsNotificationStatus = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            val granted = status == UNAuthorizationStatusAuthorized ||
                status == UNAuthorizationStatusProvisional ||
                status == UNAuthorizationStatusEphemeral
            if (continuation.isActive) {
                continuation.resume(
                    OsNotificationStatus(
                        granted = granted,
                        determined = status != null && status != UNAuthorizationStatusNotDetermined,
                    ),
                )
            }
        }
    }

    actual suspend fun request(): Boolean = suspendCancellableCoroutine { continuation ->
        val util = runCatching { NotifierManager.getPermissionUtil() }.getOrNull()
        if (util == null) {
            Napier.w("[push] NotifierManager not initialised; cannot ask for permission")
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        util.askNotificationPermission { granted ->
            if (continuation.isActive) continuation.resume(granted)
        }
    }

    actual fun openSystemSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
    }
}
