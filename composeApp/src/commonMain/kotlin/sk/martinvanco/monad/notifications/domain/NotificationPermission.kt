package sk.martinvanco.monad.notifications.domain

/** The OS notification permission as the settings screen shows it. */
enum class NotificationPermissionState {
    GRANTED,
    DENIED,
    NOT_ASKED,
}

/**
 * What the platform can say about the permission.
 *
 * iOS answers both questions (`notDetermined` is a real status). Android 13+ answers only the
 * first: a missing `POST_NOTIFICATIONS` looks the same before the dialog and after a refusal, so
 * [determined] is null there and the app's own record of having asked decides.
 */
data class OsNotificationStatus(
    val granted: Boolean,
    val determined: Boolean?,
)

/**
 * Combine the platform's answer with the app's own record of having asked.
 *
 * Pure so the three-way outcome is a test, not a guess: the state drives which button the settings
 * screen offers, and offering "Allow" to a user whose only remaining path is system settings is the
 * failure store review notices.
 */
fun resolvePermissionState(status: OsNotificationStatus, askedBefore: Boolean): NotificationPermissionState = when {
    status.granted -> NotificationPermissionState.GRANTED
    status.determined == true -> NotificationPermissionState.DENIED
    status.determined == false -> NotificationPermissionState.NOT_ASKED
    askedBefore -> NotificationPermissionState.DENIED
    else -> NotificationPermissionState.NOT_ASKED
}

/**
 * The OS permission, asked for at a moment the app chooses (IP-157).
 *
 * Never on launch. iOS asked on start whenever Firebase was present, which App Store review reads
 * as the wrong moment; the prompt now comes from `NotificationSettingsScreen` or after the first
 * completed quest, with a sentence saying what arrives.
 *
 * iOS: `NotifierManager.getPermissionUtil()` (kmpNotifier 1.6.1) wraps `UNUserNotificationCenter`.
 * Android: the library's `getPermissionUtil()` is a check-only mock; the request needs an
 * Activity-registered launcher, which `MainActivity` hands to the actual.
 */
expect class NotificationPermission() {
    suspend fun status(): OsNotificationStatus

    /** Shows the OS dialog when it can; returns the resulting grant. Never throws. */
    suspend fun request(): Boolean

    /** The app's page in system settings, for a permission only the OS can now restore. */
    fun openSystemSettings()
}
