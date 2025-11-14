package sk.martinvanco.monad.auth.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class MeResponseDto(
    val user: MeUserDto
)

@Serializable
data class MeUserDto(
    val id: String,
    val email: String,
    val name: String? = null,
    val roles: List<String> = emptyList(),
    val createdAt: String
)
