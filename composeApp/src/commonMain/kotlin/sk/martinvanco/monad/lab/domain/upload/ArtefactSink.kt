package sk.martinvanco.monad.lab.domain.upload

/**
 * Where a session artefact goes.
 *
 * The upload path's rules — upload-then-delete, streams before sidecar, bounded retry, nothing
 * discarded without an acknowledgement — are about *ordering and bookkeeping*, not about HTTP.
 * Naming what the uploader needs from the network lets those rules be checked against a real
 * database and a substituted sink, instead of against reasoning.
 *
 * A throw is a failure. The uploader catches it, counts an attempt, and keeps the bytes.
 *
 * ### Two protocols, because there are genuinely two
 *
 * [put] sends one body. That is right for a TSV and wrong for a mesh, and the boundary is the
 * *transport* rather than any limit the server sets. On 2026-08-26 a 21-minute survey walk uploaded
 * nine artefacts and lost two — `mesh.ply` (102.94 MB) and `worldmap.armap` (30.05 MB) — with no
 * error anywhere: the socket dropped mid-body, and each of the four retries restarted the same
 * doomed request. nginx admits 520 MB and PHP admits 500 MB, so nothing refused them.
 *
 * The remaining three methods are the multipart family, and what they change is **the unit of
 * loss**: a dropped connection costs one part, and the retry re-sends that part alone. They are
 * primitives rather than one composite call on purpose — the retry budget belongs to
 * [RetryPolicy] in the uploader, where it is one policy applied in one place, not a second
 * backoff hidden inside a network adapter.
 */
interface ArtefactSink {

    /** One whole body. Used below [PartedUpload.THRESHOLD_BYTES]; a throw is a failure. */
    suspend fun put(
        sessionId: String,
        participantId: String,
        artefact: String,
        content: ByteArray,
        contentType: String,
        token: String,
    )

    /** Open a parted upload for [totalBytes] of [artefact]. */
    suspend fun beginParts(
        sessionId: String,
        participantId: String,
        artefact: String,
        totalBytes: Long,
        contentType: String,
        token: String,
    ): PartedUpload

    /**
     * Send one part. [number] is 1-based.
     *
     * [isLast] is passed rather than inferred, because a server cannot tell a deliberately short
     * final part from a truncated interior one, and the difference is whether the object is whole.
     */
    suspend fun putPart(
        upload: PartedUpload,
        number: Int,
        isLast: Boolean,
        content: ByteArray,
        token: String,
    ): PartTag

    /** Seal the upload from the parts that were acknowledged. */
    suspend fun completeParts(upload: PartedUpload, tags: List<PartTag>, token: String)

    /**
     * Discard an open upload and the parts it holds.
     *
     * Part of the protocol, not housekeeping: abandoned parts stay in the object store, are billed,
     * and never appear in a listing. A phone that walks out of the room mid-upload is the normal
     * case here.
     */
    suspend fun abortParts(upload: PartedUpload, token: String)
}

/**
 * An open parted upload: what the artefact is, and the server's handle on it.
 *
 * Carries its own identity because all three follow-up calls need it, and because the *server*
 * derives the object key from these fields on every request. A key the client carried between calls
 * would be a client-chosen write path into the archive.
 */
data class PartedUpload(
    val sessionId: String,
    val participantId: String,
    val artefact: String,
    val uploadId: String,
    val totalBytes: Long,
    /** Smallest interior part the store will accept, as the store itself stated it. */
    val minPartBytes: Int,
) {
    /**
     * How the [totalBytes] are cut, given a preferred part size.
     *
     * The preferred size is raised to [minPartBytes] when the store demands more, because an
     * undersized interior part fails at *completion* — after the whole transfer has been spent —
     * rather than when it is sent.
     *
     * A total that does not divide evenly leaves a short final part, which is legal and is exactly
     * what [ArtefactSink.putPart]'s `isLast` announces. A total smaller than one part yields one
     * part, never zero: an empty manifest is refused by the store, and an artefact that produced no
     * part at all would look like a successful upload of nothing.
     */
    fun plan(preferredPartBytes: Int): List<PartSpan> {
        val size = maxOf(preferredPartBytes, minPartBytes, 1)
        if (totalBytes <= 0) return emptyList()
        val spans = mutableListOf<PartSpan>()
        var offset = 0L
        var number = 1
        while (offset < totalBytes) {
            val length = minOf(size.toLong(), totalBytes - offset).toInt()
            offset += length
            spans += PartSpan(number = number, offset = offset - length, length = length, isLast = offset >= totalBytes)
            number++
        }
        return spans
    }

    companion object {
        /**
         * Artefacts at or above this size go out in parts.
         *
         * 8 MiB. Named once, here, so the split between the two protocols is a stated boundary
         * rather than a scatter of size checks. The value is above the 5 MiB S3 interior-part floor
         * (so one part is always legal) and small enough that losing a part costs a few seconds on a
         * phone uplink. Every TSV this app writes is comfortably below it; `mesh.ply` and
         * `worldmap.armap` are the only artefacts that have ever exceeded it.
         */
        const val THRESHOLD_BYTES: Long = 8L * 1024 * 1024

        /** Part size the client asks for. Same 8 MiB — the boundary and the part are one number. */
        const val PART_BYTES: Int = 8 * 1024 * 1024
    }
}

/** One part's place in the artefact. Pure arithmetic, so the plan is testable with no network. */
data class PartSpan(val number: Int, val offset: Long, val length: Int, val isLast: Boolean)

/**
 * One acknowledged part.
 *
 * [etag] is opaque and is echoed back **verbatim**, quotes included. Normalising it is the classic
 * multipart bug: the store compares the manifest string against the one it issued, so a tidied ETag
 * fails the completion without naming which part was rewritten.
 */
data class PartTag(val number: Int, val etag: String)
