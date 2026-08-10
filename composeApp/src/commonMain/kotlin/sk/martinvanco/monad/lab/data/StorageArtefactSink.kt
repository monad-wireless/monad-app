package sk.martinvanco.monad.lab.data

import sk.martinvanco.monad.lab.domain.upload.ArtefactSink
import sk.martinvanco.monad.storage.data.api.StorageService

/**
 * The production [ArtefactSink]: the backend's S3 proxy.
 *
 * A one-line adapter, and that is the point — everything worth testing about the upload path lives
 * in `LabSessionUploader`, and none of it is HTTP.
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
}
