package sk.martinvanco.monad.storage.data.api

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.storage.data.dto.UploadResponseDto

class StorageService(private val ktorClient: KtorClient) {

    /**
     * Upload file to S3 via backend (general upload)
     *
     * @param token The auth token
     * @param filename The filename
     * @param fileContent The file content as ByteArray
     */
    suspend fun uploadFile(
        token: String,
        filename: String,
        fileContent: ByteArray
    ): UploadResponseDto {
        val response = ktorClient.client.post("/api/storage/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Filename", filename)
            contentType(ContentType.Application.OctetStream)
            setBody(fileContent)
        }
        return response.body<UploadResponseDto>()
    }

    /**
     * Upload experiment data file to S3 via backend
     *
     * @param filename The filename (e.g., "ble_data.tsv", "metadata.tsv")
     * @param experimentId The quest enrollment ID
     * @param content The file content as ByteArray
     * @param contentType The MIME type (default: text/tab-separated-values)
     * @param token The auth token
     */
    suspend fun uploadExperimentFile(
        filename: String,
        experimentId: String,
        content: ByteArray,
        contentType: String = "text/tab-separated-values",
        token: String
    ): UploadResponseDto {
        val response = ktorClient.client.post("/api/storage/experiment-upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Filename", filename)
            header("X-Experiment-Id", experimentId)
            contentType(ContentType.parse(contentType))
            setBody(content)
        }
        return response.body<UploadResponseDto>()
    }
}
