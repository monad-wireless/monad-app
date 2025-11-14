package sk.martinvanco.monad.auth.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterResponseDto(
    val message: String,
    val token: String,
    val user: UserDto
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String? = null,
    val createdAt: String
)
