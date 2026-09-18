package sk.martinvanco.monad.auth.data.dto

import kotlinx.serialization.Serializable

/**
 * What `POST /api/auth/login` returns.
 *
 * [name] is nullable for the reason set out on [MeResponseDto]: `users.name` is `?string` and
 * several ordinary paths leave it null. It was a non-nullable `String` here, so an account without
 * a name could not log in at all.
 */
@Serializable
data class LoginResponseDto(
    val email: String,
    val name: String? = null,
    val token: String,
)
