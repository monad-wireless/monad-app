package sk.martinvanco.monad.lab.domain.upload

/**
 * Where a session artefact goes.
 *
 * One method, deliberately: the upload path's rules — upload-then-delete, streams before sidecar,
 * bounded retry, nothing discarded without an acknowledgement — are about *ordering and
 * bookkeeping*, not about HTTP. Naming the one thing the uploader needs from the network lets those
 * rules be checked against a real database and a substituted sink, instead of against reasoning.
 *
 * A throw is a failure. The uploader catches it, counts an attempt, and keeps the bytes.
 */
fun interface ArtefactSink {

    suspend fun put(
        sessionId: String,
        participantId: String,
        artefact: String,
        content: ByteArray,
        contentType: String,
        token: String,
    )
}
