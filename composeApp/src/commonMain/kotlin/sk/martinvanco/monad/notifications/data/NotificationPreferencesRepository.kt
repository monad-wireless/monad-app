package sk.martinvanco.monad.notifications.data

import io.github.aakira.napier.Napier
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.data.repository.SettingsRepository
import sk.martinvanco.monad.notifications.data.api.NotificationsService
import sk.martinvanco.monad.notifications.data.dto.NotificationPreferencesDto

/**
 * The two consent toggles, owned by the backend and mirrored locally (IP-157).
 *
 * The server is the record: it is what gates a push, and a toggle the phone believes but the
 * server does not would let a callout through to somebody who turned them off. The local copy is
 * for display when there is no route out, and it is written only after the server accepted.
 *
 * Defaults follow the proposal: `general` on, `quest_callout` off until the participant opts in.
 */
class NotificationPreferencesRepository(
    private val service: NotificationsService,
    private val settings: SettingsRepository,
    private val users: UserRepository,
) {
    data class Preferences(val notifyGeneral: Boolean, val notifyCallouts: Boolean) {
        companion object {
            val DEFAULT = Preferences(notifyGeneral = true, notifyCallouts = false)
        }
    }

    /** Last copy the server confirmed, or the defaults on a fresh install. */
    suspend fun cached(): Preferences = Preferences(
        notifyGeneral = settings.getSetting(KEY_GENERAL)?.toBooleanStrictOrNull() ?: Preferences.DEFAULT.notifyGeneral,
        notifyCallouts = settings.getSetting(KEY_CALLOUTS)?.toBooleanStrictOrNull() ?: Preferences.DEFAULT.notifyCallouts,
    )

    /** Server copy, cached on success; the cached copy on any failure. */
    suspend fun fetch(): Result<Preferences> {
        val token = users.getCurrentUser()?.token
            ?: return Result.failure(IllegalStateException("not signed in"))
        return runCatching { service.getPreferences(token).toPreferences() }
            .onSuccess { store(it) }
            .onFailure { Napier.w("[prefs] fetch failed: ${it.message}") }
    }

    /** PUT, then cache. The caller keeps the old value on failure. */
    suspend fun update(preferences: Preferences): Result<Preferences> {
        val token = users.getCurrentUser()?.token
            ?: return Result.failure(IllegalStateException("not signed in"))
        return runCatching {
            service.putPreferences(
                token,
                NotificationPreferencesDto(
                    notifyGeneral = preferences.notifyGeneral,
                    notifyCallouts = preferences.notifyCallouts,
                ),
            ).toPreferences()
        }
            .onSuccess { store(it) }
            .onFailure { Napier.w("[prefs] update failed: ${it.message}") }
    }

    /** Logout: the next account starts from the defaults, not from this one's choices. */
    suspend fun clear() {
        settings.deleteSetting(KEY_GENERAL)
        settings.deleteSetting(KEY_CALLOUTS)
    }

    private suspend fun store(preferences: Preferences) {
        settings.setSetting(KEY_GENERAL, preferences.notifyGeneral.toString())
        settings.setSetting(KEY_CALLOUTS, preferences.notifyCallouts.toString())
    }

    private fun NotificationPreferencesDto.toPreferences() =
        Preferences(notifyGeneral = notifyGeneral, notifyCallouts = notifyCallouts)

    companion object {
        const val KEY_GENERAL = "notify_general"
        const val KEY_CALLOUTS = "notify_callouts"
    }
}
