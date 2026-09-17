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
) {
    suspend fun getCurrentUser(): User? {
        return userRepository.getCurrentUser()
    }

    suspend fun validateToken(token: String): Boolean {
        return try {
            authService.getMe(token)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun saveUserFromLogin(email: String, name: String, token: String) {
        userRepository.deleteAllUsers()
        userRepository.insertUser(
            backendId = null,
            email = email,
            name = name,
            token = token
        )
        sessionObserver.onSignedIn()
    }

    suspend fun saveUserFromRegister(email: String, name: String, token: String) {
        userRepository.deleteAllUsers()
        userRepository.insertUser(
            backendId = null,
            email = email,
            name = name,
            token = token
        )
        sessionObserver.onSignedIn()
    }

    suspend fun clearUser() {
        sessionObserver.onSigningOut()
        userRepository.deleteAllUsers()
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
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
