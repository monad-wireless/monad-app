package sk.martinvanco.monad.notifications.presentation.settings

import sk.martinvanco.monad.notifications.domain.NotificationPermissionState

data class NotificationSettingsState(
    val notifyGeneral: Boolean = true,
    val notifyCallouts: Boolean = false,
    val permission: NotificationPermissionState = NotificationPermissionState.NOT_ASKED,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    /** True when the toggles show the local copy because the server could not be reached. */
    val isOffline: Boolean = false,
    val error: String? = null,
)

sealed interface NotificationSettingsEvent {
    data class SetGeneral(val enabled: Boolean) : NotificationSettingsEvent
    data class SetCallouts(val enabled: Boolean) : NotificationSettingsEvent
    data object AllowNotifications : NotificationSettingsEvent
    data object OpenSystemSettings : NotificationSettingsEvent
    data object Refresh : NotificationSettingsEvent
}
