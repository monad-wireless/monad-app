package sk.martinvanco.monad.auth.data.dto

import kotlinx.serialization.Serializable

/**
 * What `POST /api/auth/register` returns.
 *
 * [name] is nullable because this endpoint echoes back what it was given, and the app's own
 * register screen sends `null` for a blank name field (`RegisterScreenModel`: `ifBlank { null }`).
 * A non-nullable `String` here therefore failed the commonest possible registration — someone who
 * left the optional name empty — with an unexplained network error.
 *
 * [token] is null when the server answers 202 with `status = "pending"`: beta access is invitation
 * only, so an email the onboarding desk did not invite creates an account that cannot sign in
 * until an operator approves it. Null is the contract, not a failure; nothing may be saved.
 */
@Serializable
data class RegisterResponseDto(
    val email: String,
    val name: String? = null,
    val token: String? = null,
    val status: String? = null,
)
