package sk.martinvanco.monad.notifications.presentation.settings

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.notifications.data.NotificationPermissionGate
import sk.martinvanco.monad.notifications.data.NotificationPreferencesRepository
import sk.martinvanco.monad.notifications.data.NotificationPreferencesRepository.Preferences
import sk.martinvanco.monad.notifications.domain.NotificationPermissionState

/**
 * Two consent toggles and the OS permission (IP-157).
 *
 * The toggles are the server's; a flip is sent first and shown second, and a refused PUT leaves the
 * switch where it was with a sentence saying so. Turning either on while the OS has never been
 * asked also puts the OS question — "prompt after the first completed quest or on a toggle" is the
 * proposal's wording — because a preference the OS will not deliver on is a promise the app cannot
 * keep.
 */
class NotificationSettingsScreenModel(
    private val preferences: NotificationPreferencesRepository,
    private val gate: NotificationPermissionGate,
) : StateScreenModel<NotificationSettingsState>(NotificationSettingsState()) {

    init {
        load()
    }

    fun onEvent(event: NotificationSettingsEvent) {
        when (event) {
            is NotificationSettingsEvent.SetGeneral -> save(current().copy(notifyGeneral = event.enabled), event.enabled)
            is NotificationSettingsEvent.SetCallouts -> save(current().copy(notifyCallouts = event.enabled), event.enabled)
            NotificationSettingsEvent.AllowNotifications -> screenModelScope.launch { askOs() }
            NotificationSettingsEvent.OpenSystemSettings -> gate.openSystemSettings()
            NotificationSettingsEvent.Refresh -> load()
        }
    }

    /** Re-read the OS state: the user may have come back from system settings. */
    fun onResume() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(permission = gate.state())
        }
    }

    private fun current() = Preferences(
        notifyGeneral = mutableState.value.notifyGeneral,
        notifyCallouts = mutableState.value.notifyCallouts,
    )

    private fun load() {
        screenModelScope.launch {
            val cached = preferences.cached()
            mutableState.value = mutableState.value.copy(
                notifyGeneral = cached.notifyGeneral,
                notifyCallouts = cached.notifyCallouts,
                permission = gate.state(),
            )
            preferences.fetch()
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        notifyGeneral = it.notifyGeneral,
                        notifyCallouts = it.notifyCallouts,
                        isLoading = false,
                        isOffline = false,
                        error = null,
                    )
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(isLoading = false, isOffline = true)
                }
        }
    }

    private fun save(wanted: Preferences, enabling: Boolean) {
        if (mutableState.value.isSaving) return
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(isSaving = true, error = null)
            if (enabling && mutableState.value.permission == NotificationPermissionState.NOT_ASKED) askOs()
            preferences.update(wanted)
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        notifyGeneral = it.notifyGeneral,
                        notifyCallouts = it.notifyCallouts,
                        isSaving = false,
                        isOffline = false,
                    )
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        isSaving = false,
                        error = "Could not save. The switch is where the server last had it.",
                    )
                }
        }
    }

    private suspend fun askOs() {
        mutableState.value = mutableState.value.copy(permission = gate.request())
    }
}
