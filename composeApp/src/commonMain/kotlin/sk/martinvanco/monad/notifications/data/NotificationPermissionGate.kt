package sk.martinvanco.monad.notifications.data

import sk.martinvanco.monad.core.data.repository.SettingsRepository
import sk.martinvanco.monad.notifications.domain.NotificationPermission
import sk.martinvanco.monad.notifications.domain.NotificationPermissionState
import sk.martinvanco.monad.notifications.domain.PushTokenRegistrar
import sk.martinvanco.monad.notifications.domain.resolvePermissionState

/**
 * The one place the OS permission is asked for (IP-157).
 *
 * Two screens can trigger the prompt — the settings toggles and the card after the first
 * completed quest — and both must do the same three things in the same order: record that the
 * question was put (Android cannot tell "not asked" from "refused" otherwise), show the dialog,
 * and register the push token if the answer was yes, because a token registered before the grant
 * is a token the OS will never deliver to.
 */
class NotificationPermissionGate(
    private val permission: NotificationPermission,
    private val settings: SettingsRepository,
    private val registrar: PushTokenRegistrar,
) {
    suspend fun state(): NotificationPermissionState {
        val status = runCatching { permission.status() }.getOrNull()
            ?: return NotificationPermissionState.NOT_ASKED
        val asked = settings.getSetting(SettingsRepository.KEY_NOTIFICATION_PERMISSION_ASKED) == "true"
        return resolvePermissionState(status, asked)
    }

    /** Ask, remember that we asked, and register the token on a grant. Returns the new state. */
    suspend fun request(): NotificationPermissionState {
        settings.setSetting(SettingsRepository.KEY_NOTIFICATION_PERMISSION_ASKED, "true")
        val granted = runCatching { permission.request() }.getOrDefault(false)
        if (granted) registrar.registerCurrent()
        return state()
    }

    fun openSystemSettings() {
        runCatching { permission.openSystemSettings() }
    }
}
