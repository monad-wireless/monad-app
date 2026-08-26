package sk.martinvanco.monad.storage.data.dto

import kotlinx.serialization.Serializable

/**
 * What `POST /api/storage/session-upload/begin` answers with.
 *
 * [partSizeHint] is S3's minimum interior part size as the *server* states it. The client picks its
 * own part size at or above that hint rather than hardcoding 5 MiB, so the floor lives in one place
 * — the storage service that has to satisfy it.
 */
@Serializable
data class MultipartBeginDto(
    val uploadId: String,
    val objectKey: String,
    val partSizeHint: Int = 0,
)

/**
 * One stored part, and the ETag the completion manifest must name it by.
 *
 * The ETag is opaque and is echoed back verbatim, quotes included. Stripping them is the classic
 * multipart bug: S3 compares the manifest string against the one it issued, so a "tidied" ETag
 * fails the completion with no indication of which part was rewritten.
 */
@Serializable
data class MultipartPartDto(
    val partNumber: Int,
    val etag: String,
    val size: Long = 0,
)

/** The manifest body of `POST /api/storage/session-upload/complete`. */
@Serializable
data class MultipartCompleteRequestDto(
    val uploadId: String,
    val parts: List<MultipartPartDto>,
)

/** The body of `POST /api/storage/session-upload/abort`. */
@Serializable
data class MultipartAbortRequestDto(val uploadId: String)

/** What the completion answers with. `parts` is what the server actually sealed, not what was sent. */
@Serializable
data class MultipartCompleteDto(
    val success: Boolean = false,
    val objectKey: String = "",
    val url: String = "",
    val parts: Int = 0,
)
