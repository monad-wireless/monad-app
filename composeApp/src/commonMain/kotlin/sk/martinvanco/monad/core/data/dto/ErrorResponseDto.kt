package sk.martinvanco.monad.core.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponseDto(
    val code: String? = null,
    val message: String? = null,
    val error: String? = null
)
