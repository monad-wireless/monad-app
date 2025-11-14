package sk.martinvanco.monad.core.util

object EmailValidator {
    private val EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()

    fun isValid(email: String): Boolean {
        return email.isNotBlank() && EMAIL_REGEX.matches(email.trim())
    }
}
