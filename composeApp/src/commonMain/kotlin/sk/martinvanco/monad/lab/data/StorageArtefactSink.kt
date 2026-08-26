package sk.martinvanco.monad.lab.data

import sk.martinvanco.monad.lab.domain.upload.ArtefactSink
import sk.martinvanco.monad.lab.domain.upload.PartTag
import sk.martinvanco.monad.lab.domain.upload.PartedUpload
import sk.martinvanco.monad.storage.data.api.StorageService
import sk.martinvanco.monad.storage.data.dto.MultipartPartDto

/**
 * The production [ArtefactSink]: the backend's S3 proxy.
 *
 * Adapters and nothing else, and that is the point — everything worth testing about the upload path
 * (ordering, the retry budget, the part plan, what is deleted and when) lives in
 * [LabSessionUploader] and in `PartedUpload`, and none of it is HTTP.
 */
class StorageArtefactSink(
    private val storage: StorageService,
) : ArtefactSink {

    override suspend fun put(
        sessionId: String,
        participantId: String,
        artefact: String,
        content: ByteArray,
        contentType: String,
        token: String,
    ) {
        storage.uploadSessionFile(
            sessionId = sessionId,
            participantId = participantId,
            filename = artefact,
            content = content,
            contentType = contentType,
            token = token,
        )
    }

    override suspend fun beginParts(
        sessionId: String,
        participantId: String,
        artefact: String,
        totalBytes: Long,
        contentType: String,
        token: String,
    ): PartedUpload {
        val opened = storage.beginSessionMultipart(
            sessionId = sessionId,
            participantId = participantId,
            filename = artefact,
            totalBytes = totalBytes,
            contentType = contentType,
            token = token,
        )
        return PartedUpload(
            sessionId = sessionId,
            participantId = participantId,
            artefact = artefact,
            uploadId = opened.uploadId,
            totalBytes = totalBytes,
            // The server states the floor; the client does not assume 5 MiB. A store with a
            // different minimum then costs a config change rather than a release.
            minPartBytes = opened.partSizeHint,
        )
    }

    override suspend fun putPart(
        upload: PartedUpload,
        number: Int,
        isLast: Boolean,
        content: ByteArray,
        token: String,
    ): PartTag {
        val stored = storage.uploadSessionPart(
            sessionId = upload.sessionId,
            participantId = upload.participantId,
            filename = upload.artefact,
            uploadId = upload.uploadId,
            partNumber = number,
            isLast = isLast,
            content = content,
            token = token,
        )
        return PartTag(number = stored.partNumber, etag = stored.etag)
    }

    override suspend fun completeParts(upload: PartedUpload, tags: List<PartTag>, token: String) {
        storage.completeSessionMultipart(
            sessionId = upload.sessionId,
            participantId = upload.participantId,
            filename = upload.artefact,
            uploadId = upload.uploadId,
            totalBytes = upload.totalBytes,
            parts = tags.map { MultipartPartDto(partNumber = it.number, etag = it.etag) },
            token = token,
        )
    }

    override suspend fun abortParts(upload: PartedUpload, token: String) {
        storage.abortSessionMultipart(
            sessionId = upload.sessionId,
            participantId = upload.participantId,
            filename = upload.artefact,
            uploadId = upload.uploadId,
            token = token,
        )
    }
}
