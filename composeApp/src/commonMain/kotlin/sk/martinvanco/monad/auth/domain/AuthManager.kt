package sk.martinvanco.monad.auth.domain

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

    suspend fun clearUser() {
        sessionObserver.onSigningOut()
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
}
