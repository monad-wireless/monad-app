package sk.martinvanco.monad.auth.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponseDto(
    val code: Int? = null,
    val message: String? = null,
    val error: String? = null
)
