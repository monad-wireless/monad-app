package sk.martinvanco.monad.auth.domain

import sk.martinvanco.monad.User
import sk.martinvanco.monad.auth.data.api.AuthService
import sk.martinvanco.monad.auth.data.repository.UserRepository

class AuthManager(
    private val userRepository: UserRepository,
    private val authService: AuthService
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
    }

    suspend fun saveUserFromRegister(email: String, name: String, token: String) {
        userRepository.deleteAllUsers()
        userRepository.insertUser(
            backendId = null,
            email = email,
            name = name,
            token = token
        )
    }

    suspend fun clearUser() {
        userRepository.deleteAllUsers()
    }
}
