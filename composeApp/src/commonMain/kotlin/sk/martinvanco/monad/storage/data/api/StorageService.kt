package sk.martinvanco.monad.storage.data.api

import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import sk.martinvanco.monad.core.data.remote.KtorClient

@Serializable
data class UploadResponseDto(
    val success: Boolean,
    val objectKey: String? = null,
    val url: String? = null,
    val size: Long? = null,
    val contentType: String? = null
)

class StorageService(private val ktorClient: KtorClient) {

    suspend fun uploadFile(
        token: String,
        filename: String,
        fileContent: ByteArray
    ): UploadResponseDto {
        val response = ktorClient.client.post("/api/storage/upload") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append("X-Filename", filename)
            }
            contentType(ContentType.Application.OctetStream)
            setBody(fileContent)
        }
        return response.body<UploadResponseDto>()
    }
}
