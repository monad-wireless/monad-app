package sk.martinvanco.monad.auth.data.dto

import kotlinx.serialization.Serializable

/**
 * What `POST /api/auth/register` returns.
 *
 * [name] is nullable because this endpoint echoes back what it was given, and the app's own
 * register screen sends `null` for a blank name field (`RegisterScreenModel`: `ifBlank { null }`).
 * A non-nullable `String` here therefore failed the commonest possible registration — someone who
 * left the optional name empty — with an unexplained network error.
 */
@Serializable
data class RegisterResponseDto(
    val email: String,
    val name: String? = null,
    val token: String,
)
