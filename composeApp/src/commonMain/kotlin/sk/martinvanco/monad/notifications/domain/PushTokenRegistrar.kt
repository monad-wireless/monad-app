package sk.martinvanco.monad.notifications.domain

import com.mmk.kmpnotifier.notification.NotifierManager
import com.mmk.kmpnotifier.notification.PayloadData
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The push token's lifecycle against the backend (IP-157).
 *
 * Three rules, and every method keeps all of them:
 *
 * 1. **A token is registered only for a signed-in user.** Without an auth token there is nothing to
 *    attach it to, so a refresh that arrives while signed out is dropped and picked up at the next
 *    login, when [registerCurrent] reads the token afresh.
 * 2. **A token is deleted on logout and on account deletion**, so a handset that changes owner does
 *    not receive the previous owner's inbox. Deletion has to run *before* the local user row is
 *    cleared, because it needs the bearer token — see `NotificationsSessionObserver`.
 * 3. **Nothing here throws.** A failed PUT costs a push until the next launch; a failed DELETE is
 *    logged and the backend's `revoked_at` is the operator's to set. Neither is allowed to block a
 *    login, a logout, or an account deletion.
 */
class PushTokenRegistrar(
    private val gateway: PushTokenGateway,
    private val credentials: PushCredentials,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Read the current FCM token and PUT it. No-op when signed out or when Firebase has no token. */
    suspend fun registerCurrent(): Boolean {
        val pushToken = safely("read push token") { credentials.pushToken() } ?: return false
        return onNewToken(pushToken)
    }

    /** PUT one specific token: the refresh path, and the body of [registerCurrent]. */
    suspend fun onNewToken(pushToken: String): Boolean {
        val authToken = safely("read auth token") { credentials.authToken() } ?: return false
        val handsetId = safely("read handset id") { credentials.handsetId() }
        return safely("register push token") {
            gateway.register(authToken, pushToken, credentials.platform(), handsetId)
            true
        } ?: false
    }

    /** DELETE the current token while the bearer token is still valid. */
    suspend fun unregisterCurrent(): Boolean {
        val authToken = safely("read auth token") { credentials.authToken() } ?: return false
        val pushToken = safely("read push token") { credentials.pushToken() } ?: return false
        return safely("unregister push token") {
            gateway.unregister(authToken, pushToken)
            true
        } ?: false
    }

    /**
     * Install the process-wide kmpNotifier listener. Replaces the log-only listener `App.kt` held.
     *
     * Token refreshes go to [onNewToken]. A tapped notification's payload is parked as a
     * [PushRoute] for the Navigator to drain. A push received while the app is open calls
     * [onPushReceived], which the inbox uses to refresh; the listener itself knows nothing about
     * the inbox. Idempotent by construction: kmpNotifier keeps a list, so this is called once.
     */
    fun install(onPushReceived: () -> Unit = {}) {
        NotifierManager.addListener(object : NotifierManager.Listener {
            override fun onNewToken(token: String) {
                Napier.i("[push] token refreshed")
                scope.launch { this@PushTokenRegistrar.onNewToken(token) }
            }

            override fun onPushNotificationWithPayloadData(title: String?, body: String?, data: PayloadData) {
                Napier.i("[push] received: ${title ?: "(no title)"}")
                onPushReceived()
            }

            override fun onNotificationClicked(data: PayloadData) {
                PendingPushRoute.parkPayload(data)
            }
        })
    }

    private suspend fun <T> safely(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Napier.w("[push] could not $what: ${e.message}")
        null
    }
}
