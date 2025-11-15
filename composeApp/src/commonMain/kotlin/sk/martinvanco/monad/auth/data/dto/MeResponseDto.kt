package sk.martinvanco.monad.auth.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class MeResponseDto(
    val email: String,
    val name: String
)
