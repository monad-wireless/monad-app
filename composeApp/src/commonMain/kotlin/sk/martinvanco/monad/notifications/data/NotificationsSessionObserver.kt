package sk.martinvanco.monad.notifications.data

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sk.martinvanco.monad.auth.domain.SessionObserver
import sk.martinvanco.monad.notifications.domain.PushTokenRegistrar

/**
 * Ties the push token and the inbox to the account's lifetime (IP-157).
 *
 * Sign-in: the PUT and the first inbox fetch are launched, not awaited, so a slow backend delays
 * neither the navigation to Home nor the login button's spinner.
 *
 * Sign-out: the DELETE is awaited under a short cap, because it must happen while the bearer token
 * is still valid and it must never hold a logout hostage to a dead network. What the cap costs is
 * one stale token row the backend's `revoked_at` can tidy; what it buys is a logout that always
 * finishes. The local inbox and preference copies are cleared after, unconditionally.
 */
class NotificationsSessionObserver(
    private val registrar: PushTokenRegistrar,
    private val inbox: NotificationInbox,
    private val preferences: NotificationPreferencesRepository,
) : SessionObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun onSignedIn() {
        scope.launch { registrar.registerCurrent() }
        scope.launch { inbox.refresh() }
    }

    override suspend fun onSigningOut() {
        val deleted = withTimeoutOrNull(UNREGISTER_CAP_MS) { registrar.unregisterCurrent() }
        if (deleted != true) Napier.w("[push] token not confirmed deleted at sign-out")
        runCatching { inbox.clear() }
        runCatching { preferences.clear() }
    }

    private companion object {
        const val UNREGISTER_CAP_MS = 3_000L
    }
}
