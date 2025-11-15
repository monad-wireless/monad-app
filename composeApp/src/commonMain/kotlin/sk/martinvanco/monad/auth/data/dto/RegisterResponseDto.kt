package sk.martinvanco.monad.auth.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterResponseDto(
    val email: String,
    val name: String,
    val token: String
)
