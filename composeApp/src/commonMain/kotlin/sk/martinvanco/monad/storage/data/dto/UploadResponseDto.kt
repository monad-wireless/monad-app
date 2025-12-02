package sk.martinvanco.monad.storage.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class UploadResponseDto(
    val success: Boolean,
    val objectKey: String,
    val url: String,
    val size: Long,
    val contentType: String
)
