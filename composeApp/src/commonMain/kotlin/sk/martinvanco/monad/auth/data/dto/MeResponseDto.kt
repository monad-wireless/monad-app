package sk.martinvanco.monad.auth.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `GET /api/auth/me` returns.
 *
 * [name] is NULLABLE and that is the backend's shape, not a defensive guess: `users.name` is
 * `?string`, `POST /api/auth/register` accepts a body without one, `app:user:create` has a `--name`
 * option nobody has to pass, and `User::softDelete()` sets the column to null outright. The app's
 * own register screen sends `null` for a blank field. Every one of those produced `"name": null`
 * against a non-nullable `String` here, which kotlinx.serialization refuses — so signing in on an
 * account with no name failed with a bare "Network error" and no way past it.
 *
 * [isOperator] is a capability, not a role string: may this account reach the operator half of the
 * app. It reuses `ROLE_SUPERADMIN` server-side, the same grant `GET /api/quests` filters operator
 * takes on. Defaulted false so a backend that predates the field locks the operator surfaces
 * rather than opening them — the safe direction for a missing value.
 *
 * It is NOT the authorization. The server gates every operator surface that touches it on its own;
 * this field only decides which doors the app draws.
 */
@Serializable
data class MeResponseDto(
    val email: String,
    val name: String? = null,
    @SerialName("is_operator") val isOperator: Boolean = false,
)
