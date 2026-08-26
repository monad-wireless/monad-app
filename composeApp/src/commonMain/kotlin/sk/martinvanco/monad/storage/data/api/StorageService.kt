package sk.martinvanco.monad.storage.data.api

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.storage.data.dto.MultipartAbortRequestDto
import sk.martinvanco.monad.storage.data.dto.MultipartBeginDto
import sk.martinvanco.monad.storage.data.dto.MultipartCompleteDto
import sk.martinvanco.monad.storage.data.dto.MultipartCompleteRequestDto
import sk.martinvanco.monad.storage.data.dto.MultipartPartDto
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
            // The default client timeout is sized to fail fast on a phone with no route out at all;
            // a real binary artefact needs the opposite (see AppConfig.UPLOAD_REQUEST_TIMEOUT).
            timeout { requestTimeoutMillis = AppConfig.UPLOAD_REQUEST_TIMEOUT }
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
            // Generous even here: a traffic.tsv from a long session is megabytes, and the default
            // client timeout is sized to fail fast on a phone with no route out at all rather than to
            // carry a transfer (see AppConfig.UPLOAD_REQUEST_TIMEOUT). Artefacts that run into the
            // hundreds of megabytes do NOT come through this method — see the multipart family
            // below, and `LabSessionUploader.PART_THRESHOLD_BYTES` for where the split is decided.
            timeout { requestTimeoutMillis = AppConfig.UPLOAD_REQUEST_TIMEOUT }
            setBody(content)
        }
        return response.body<UploadResponseDto>()
    }

    // ---- multipart: the large-artefact path -------------------------------------------------
    //
    // WHY IT EXISTS. `uploadSessionFile` above sends one body, and that works right up to the point
    // where the *connection* gives out. On 2026-08-26 a 21-minute survey walk uploaded nine
    // artefacts and lost two — `mesh.ply` (102.94 MB) and `worldmap.armap` (30.05 MB) — with no
    // error in this app, in the backend, or in the bucket: the socket dropped mid-body and each of
    // the four retries restarted the same doomed request. The size cliff was clean, because every
    // small artefact went and every large one did not.
    //
    // The property that fixes it is not "multipart" but **the unit of loss**: a dropped connection
    // costs one part, and a retry re-sends that part alone. Three calls in sequence — begin, one
    // part per chunk, complete — plus an abort for the give-up path, because abandoned parts stay in
    // the bucket, are billed, and never appear in an object listing.

    /**
     * Open a multipart upload and get back its id.
     *
     * [totalBytes] travels on a header so the size is validated *before* any part is transferred.
     * The artefact's own content type travels on `X-Artefact-Content-Type` rather than on
     * `Content-Type`, because this request has no body and the two facts are different.
     */
    suspend fun beginSessionMultipart(
        sessionId: String,
        participantId: String,
        filename: String,
        totalBytes: Long,
        contentType: String,
        token: String,
    ): MultipartBeginDto = ktorClient.client.post("/api/storage/session-upload/begin") {
        header(HttpHeaders.Authorization, "Bearer $token")
        header("X-Filename", filename)
        header("X-Session-Id", sessionId)
        header("X-Participant-Id", participantId)
        header("X-Artefact-Content-Type", contentType)
        header("X-Total-Bytes", totalBytes.toString())
    }.body()

    /**
     * Send one part. [partNumber] is 1-based, and every part except the last must be at least the
     * `partSizeHint` the begin call returned.
     *
     * [isLast] is sent rather than inferred, because the server cannot tell a deliberately short
     * final part from a truncated interior one — and the difference is whether the object is whole.
     */
    suspend fun uploadSessionPart(
        sessionId: String,
        participantId: String,
        filename: String,
        uploadId: String,
        partNumber: Int,
        isLast: Boolean,
        content: ByteArray,
        token: String,
    ): MultipartPartDto = ktorClient.client.post("/api/storage/session-upload/part") {
        header(HttpHeaders.Authorization, "Bearer $token")
        header("X-Filename", filename)
        header("X-Session-Id", sessionId)
        header("X-Participant-Id", participantId)
        header("X-Upload-Id", uploadId)
        header("X-Part-Number", partNumber.toString())
        header("X-Last-Part", isLast.toString())
        contentType(ContentType.Application.OctetStream)
        // Per PART, not per artefact. One part is bounded work, so a stalled connection is detected
        // in part time rather than after the whole transfer's budget has elapsed — which is what
        // turns "the upload hung" into "part 7 hung, resend part 7".
        timeout { requestTimeoutMillis = AppConfig.UPLOAD_PART_TIMEOUT }
        setBody(content)
    }.body()

    /** Seal the upload. [parts] may be in any order; the server sorts before building the manifest. */
    suspend fun completeSessionMultipart(
        sessionId: String,
        participantId: String,
        filename: String,
        uploadId: String,
        totalBytes: Long,
        parts: List<MultipartPartDto>,
        token: String,
    ): MultipartCompleteDto = ktorClient.client.post("/api/storage/session-upload/complete") {
        header(HttpHeaders.Authorization, "Bearer $token")
        header("X-Filename", filename)
        header("X-Session-Id", sessionId)
        header("X-Participant-Id", participantId)
        header("X-Total-Bytes", totalBytes.toString())
        contentType(ContentType.Application.Json)
        setBody(MultipartCompleteRequestDto(uploadId = uploadId, parts = parts))
    }.body()

    /**
     * Discard an open upload and the parts it holds.
     *
     * Part of the protocol, not tidy-up. A phone that walks out of the room mid-upload is the normal
     * case here, and an abandoned multipart upload is invisible storage that nobody will find.
     */
    suspend fun abortSessionMultipart(
        sessionId: String,
        participantId: String,
        filename: String,
        uploadId: String,
        token: String,
    ) {
        ktorClient.client.post("/api/storage/session-upload/abort") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Filename", filename)
            header("X-Session-Id", sessionId)
            header("X-Participant-Id", participantId)
            contentType(ContentType.Application.Json)
            setBody(MultipartAbortRequestDto(uploadId = uploadId))
        }
    }
}
