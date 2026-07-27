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
     * Upload one artefact of a lab session (EXP-P3).
     *
     * Artefacts land under `datasets/monad-app-sessions/<participant>/<session>/<filename>` so a
     * phone session sits beside the `csid` fleet captures in the same bucket, addressable the same
     * way. Replaces the date-partitioned `experiment-upload` layout, which keyed data by upload
     * date rather than by session and could not be joined to a `csid` capture.
     */
    suspend fun uploadSessionFile(
        sessionId: String,
        participantId: String,
        filename: String,
        content: ByteArray,
        contentType: String = "text/tab-separated-values",
        token: String
    ): UploadResponseDto {
        val response = ktorClient.client.post("/api/storage/session-upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Filename", filename)
            header("X-Session-Id", sessionId)
            header("X-Participant-Id", participantId)
            contentType(ContentType.parse(contentType))
            setBody(content)
        }
        return response.body<UploadResponseDto>()
    }
}
