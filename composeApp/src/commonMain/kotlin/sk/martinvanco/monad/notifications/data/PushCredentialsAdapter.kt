package sk.martinvanco.monad.notifications.data

import com.mmk.kmpnotifier.notification.NotifierManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.util.Platform
import sk.martinvanco.monad.lab.domain.HandsetIdentity
import sk.martinvanco.monad.notifications.domain.PushCredentials

/**
 * The production [PushCredentials]: the user table, the installation identity, and kmpNotifier.
 *
 * `getPushNotifier()` throws when the library was never initialised, and `getToken()` returns null
 * on a build without Firebase. Both are "no token", which the registrar treats as nothing to send.
 */
class PushCredentialsAdapter(
    private val users: UserRepository,
    private val handset: HandsetIdentity,
    /**
     * The process scope, NOT this call's scope. See [pushToken]: the Firebase read has to outlive
     * the caller that gave up on it, and a child coroutine cannot — structured concurrency waits
     * for children whether or not they were cancelled.
     */
    private val scope: CoroutineScope,
) : PushCredentials {

    override suspend fun authToken(): String? = users.getCurrentUser()?.token

    override suspend fun handsetId(): String? = runCatching { handset.id() }.getOrNull()

    /**
     * The FCM token, or null — and it ALWAYS answers.
     *
     * ## Why this is not simply the one-liner it was
     *
     * On iOS, kmpNotifier's `getToken()` asks Firebase Messaging and waits for its callback inside
     * a plain `suspendCoroutine`. On a build with **no `GoogleService-Info.plist`** — every bench
     * build, every CI build, and any handset flashed from a clean checkout — Firebase is never
     * configured, the callback never fires, and that suspension never resumes.
     *
     * A plain `suspendCoroutine` has no cancellation handler, so it cannot be cancelled. That is
     * the part that made this expensive: the caller's `withTimeoutOrNull(3_000)` fired on time and
     * could not interrupt anything, so `PushTokenRegistrar.unregisterCurrent` hung, and with it
     * `SessionObserver.onSigningOut`, `AuthManager.clearUser`, and the splash coroutine that calls
     * it. **The app sat on its launch spinner for ever**, with no crash, no log and no busy thread
     * — measured on a device on 2026-09-18, the day a stored JWT expired and sign-out became the
     * normal launch path.
     *
     * ## How it is bounded, and why the obvious version does not work
     *
     * The read runs on the **process scope**, so it is not a child of this call, and the caller
     * waits on `await()` — which IS cancellable whatever the work behind it is doing. The timeout
     * therefore releases the caller on time.
     *
     * Wrapping it in `coroutineScope { async { … } }` was tried first and measured on a device: the
     * timeout logged at exactly 2.0 s and the function still did not return, because
     * `coroutineScope` does not complete until every child does, and a child parked on an
     * uncancellable suspension never does. `cancel()` did not help either — it marks a coroutine
     * cancelled, it cannot evict it from a suspension that has no cancellation handler. The stall
     * simply moved up the stack: 2 s here, 3 s in `AuthManager`, 5 s in the splash, each cap firing
     * on time and each scope then waiting anyway.
     *
     * So the orphan is detached and left to finish or not. One idle coroutine on a build with no
     * Firebase is a far smaller price than an app that cannot be opened, and on a build where
     * Firebase works the read returns in milliseconds and nothing is orphaned at all.
     *
     * A timed-out read is reported as "no token", which is what it is: this handset cannot name a
     * push token right now. The registrar already treats that as nothing to send.
     */
    override suspend fun pushToken(): String? {
        val read = scope.async(Dispatchers.Default) {
            runCatching { NotifierManager.getPushNotifier().getToken() }.getOrNull()
        }
        val token = withTimeoutOrNull(TOKEN_READ_CAP_MS) { read.await() }
        if (token == null) {
            Napier.i("[push] token unavailable within $TOKEN_READ_CAP_MS ms — treating as no token")
        }
        return token?.ifBlank { null }
    }

    override fun platform(): String = if (Platform.isIOS) "ios" else "android"

    private companion object {
        /**
         * Two seconds. Comfortably inside the caller's own 3 s sign-out cap, so the bound that
         * actually works is the inner one rather than the outer one that cannot interrupt it.
         */
        const val TOKEN_READ_CAP_MS = 2_000L
    }
}
