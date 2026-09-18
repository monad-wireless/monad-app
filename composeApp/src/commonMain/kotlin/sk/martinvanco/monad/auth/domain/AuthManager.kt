package sk.martinvanco.monad.auth.domain

import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import sk.martinvanco.monad.User
import sk.martinvanco.monad.auth.data.api.AuthService
import sk.martinvanco.monad.auth.data.repository.UserRepository

/**
 * The account's lifetime on this handset.
 *
 * [sessionObserver] is told after a user row is written and before one is cleared (IP-157). The
 * order matters on the way out: the push token can only be deleted on the server while the bearer
 * token still exists, so the observer runs first and the row goes second.
 */
class AuthManager(
    private val userRepository: UserRepository,
    private val authService: AuthService,
    private val sessionObserver: SessionObserver,
    private val operatorAccess: OperatorAccess,
) {
    suspend fun getCurrentUser(): User? {
        return userRepository.getCurrentUser()
    }

    /**
     * Is this token still good, and what is this account allowed to see?
     *
     * The one call already answers both, so the operator capability is recorded here rather than
     * from a second round trip. A refresh that fails leaves the cached value alone: the token being
     * unreachable is not evidence that a grant was withdrawn, and revoking the console because a
     * phone was on a lab network with no route out would be a failure mode of its own.
     */
    suspend fun validateToken(token: String): Boolean {
        return try {
            val me = authService.getMe(token)
            operatorAccess.remember(me.isOperator)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun saveUserFromLogin(email: String, name: String?, token: String) {
        userRepository.deleteAllUsers()
        userRepository.insertUser(
            backendId = null,
            email = email,
            name = name,
            token = token
        )
        // Sign-in does not itself say what the account may reach, so ask. A failure leaves the
        // participant default in place and the next launch tries again.
        runCatching { authService.getMe(token) }
            .onSuccess { operatorAccess.remember(it.isOperator) }
        sessionObserver.onSignedIn()
    }

    suspend fun saveUserFromRegister(email: String, name: String?, token: String) {
        userRepository.deleteAllUsers()
        userRepository.insertUser(
            backendId = null,
            email = email,
            name = name,
            token = token
        )
        // A freshly registered account is always ROLE_USER — `/api/auth/register` cannot mint
        // anything else — so this is the cheap, correct answer rather than a round trip.
        operatorAccess.remember(false)
        sessionObserver.onSignedIn()
    }

    /**
     * Drop the account on this handset.
     *
     * The observer runs first and the row goes second, and that order is deliberate: the push
     * token can only be deleted on the server while the bearer token still exists. What is NEW is
     * that the observer is **bounded**, and the local deletion happens whether or not it finished.
     *
     * The observer reaches the network and a push SDK. On 2026-09-18 that path stopped returning
     * at all — a Firebase token read suspended in a non-cancellable `suspendCoroutine` on a build
     * with no `GoogleService-Info.plist` — and because the deletion waited behind it, `clearUser`
     * never returned, the splash coroutine that calls it never returned, and the app sat on its
     * launch spinner for ever. [PushCredentialsAdapter] fixes that particular read; this bound is
     * the structural half, so the next slow step in sign-out costs a few seconds rather than the
     * app.
     *
     * `async` plus `await` rather than a timeout wrapped straight around the call, because
     * `await()` is cancellable whatever the work behind it is doing. The deletion is NOT bounded
     * and must not be: a local row that survives a sign-out would be handed to the next account.
     */
    suspend fun clearUser() {
        coroutineScope {
            val cleanup = async { runCatching { sessionObserver.onSigningOut() } }
            if (withTimeoutOrNull(SIGN_OUT_CLEANUP_CAP_MS) { cleanup.await() } == null) {
                Napier.w("[auth] sign-out cleanup unfinished after $SIGN_OUT_CLEANUP_CAP_MS ms; dropping the account anyway")
                // Cancel, or the cap buys nothing: `coroutineScope` does not return until every
                // child has finished, so an abandoned-but-running cleanup would hold this function
                // open for exactly as long as the timeout was supposed to prevent. Measured: the
                // timeout fired at 3.0 s and `clearUser` still returned at 5.0 s.
                cleanup.cancel()
            }
        }
        userRepository.deleteAllUsers()
        operatorAccess.clear()
    }

    suspend fun deleteAccount(): Result<Unit> {
        return try {
            val user = userRepository.getCurrentUser() ?: return Result.failure(Exception("No user found"))
            val token = user.token ?: return Result.failure(Exception("No token found"))
            // Push token first: once the account is gone the bearer token is refused, and a token
            // row nobody can delete would keep addressing a handset that no longer has an owner.
            sessionObserver.onSigningOut()
            authService.deleteAccount(token)
            userRepository.deleteAllUsers()
            operatorAccess.clear()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        /**
         * Three seconds for the sign-out observer. It matches the cap the observer already applies
         * to its own push-token unregister, so this is the outer bound that still works when an
         * inner one cannot cancel what it is waiting on.
         */
        const val SIGN_OUT_CLEANUP_CAP_MS = 3_000L
    }
}
