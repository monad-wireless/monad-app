package sk.martinvanco.monad.auth.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /api/auth/password`. The server checks [newPassword] is 8 to 255 characters. */
@Serializable
data class ChangePasswordRequestDto(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)
