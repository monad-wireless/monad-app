package sk.martinvanco.monad.lab.data

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.lab.domain.LabArtefact
import sk.martinvanco.monad.storage.data.api.StorageService

/**
 * Uploads a closed session's artefacts, then — and only then — releases the local copy.
 *
 * The rule is **upload-then-delete**, and it is the whole reason this class exists. The app's
 * older quest path exported to TSV, posted it, and dropped the local rows on the completion path
 * regardless of what the server said; a failed upload therefore discarded the only copy of a
 * field session. Here a failure marks the session `failed`, leaves every byte on disk, and
 * surfaces it in the lab console as unsynced until it succeeds.
 *
 * The sidecar is uploaded **last**. Its presence in the object store is then a reliable marker
 * that the session is complete, which lets the reader side treat a prefix without one as a
 * partial upload rather than as a session with missing streams.
 */
class LabSessionUploader(
    private val repository: LabSessionRepository,
    private val storage: StorageService,
    private val users: UserRepository,
) {
    private val _progress = MutableStateFlow<UploadProgress?>(null)
    val progress: StateFlow<UploadProgress?> = _progress.asStateFlow()

    /** Upload every closed or previously-failed session. Returns how many fully succeeded. */
    suspend fun uploadPending(purgeAfter: Boolean = true): Int {
        val token = users.getCurrentUser()?.token
        if (token.isNullOrBlank()) {
            Napier.w("[lab] upload skipped: not authenticated")
            return 0
        }
        val pending = repository.pendingUpload()
        var uploaded = 0
        pending.forEachIndexed { index, record ->
            _progress.value = UploadProgress(record.sessionId, index + 1, pending.size, LabArtefact.SIDECAR)
            val result = uploadOne(record.sessionId, token, purgeAfter)
            if (result) uploaded++
        }
        _progress.value = null
        return uploaded
    }

    suspend fun uploadOne(sessionId: String, token: String, purgeAfter: Boolean = true): Boolean {
        val record = repository.byId(sessionId) ?: return false
        val sidecar = record.sidecarJson
        if (sidecar.isNullOrBlank()) {
            repository.markFailed(sessionId, "session has no sidecar — was it closed?")
            return false
        }

        return runCatching {
            // Streams first, sidecar last: the sidecar's presence marks completeness.
            put(sessionId, LabArtefact.TRAFFIC, repository.trafficTsv(sessionId), TSV, token, record.participantId)
            put(sessionId, LabArtefact.BEACONS, repository.beaconsTsv(sessionId), TSV, token, record.participantId)
            put(sessionId, LabArtefact.TRANSITIONS, repository.transitionsTsv(sessionId), TSV, token, record.participantId)
            put(sessionId, LabArtefact.CLOCK, repository.clockTsv(sessionId), TSV, token, record.participantId)
            put(sessionId, LabArtefact.SIDECAR, sidecar.encodeToByteArray(), JSON, token, record.participantId)
        }.fold(
            onSuccess = {
                repository.markUploaded(sessionId)
                if (purgeAfter) repository.purgeUploaded(sessionId)
                Napier.i("[lab] session $sessionId uploaded${if (purgeAfter) " and purged" else ""}")
                true
            },
            onFailure = { error ->
                val message = error.message ?: error::class.simpleName ?: "unknown"
                repository.markFailed(sessionId, message)
                Napier.w("[lab] session $sessionId upload failed, data retained: $message")
                false
            },
        )
    }

    private suspend fun put(
        sessionId: String,
        filename: String,
        content: ByteArray,
        contentType: String,
        token: String,
        participantId: String,
    ) {
        _progress.value = _progress.value?.copy(artefact = filename)
        storage.uploadSessionFile(
            sessionId = sessionId,
            participantId = participantId,
            filename = filename,
            content = content,
            contentType = contentType,
            token = token,
        )
    }

    private companion object {
        const val TSV = "text/tab-separated-values"
        const val JSON = "application/json"
    }
}

data class UploadProgress(
    val sessionId: String,
    val index: Int,
    val total: Int,
    val artefact: String,
)
