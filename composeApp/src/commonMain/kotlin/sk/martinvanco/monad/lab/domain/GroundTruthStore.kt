package sk.martinvanco.monad.lab.domain

/**
 * Where a ground-truth scan is buffered, as the recorder needs it.
 *
 * Three methods, and the asymmetry is the point: the recorder may **read this participant's own
 * history** — that is what resolves a `toggle` code and what makes "entering a zone leaves the
 * previous one" derivable — and it may **write a scan**. It cannot mark a row uploaded, cannot
 * render the TSV, and cannot read anybody else's scans. Those belong to the uploader and the
 * console, and keeping them out of reach is what stops the people channel from ever being derived
 * from something other than a human scanning a printed code.
 */
interface GroundTruthStore {

    /** Buffer one scan. Idempotent on [GroundTruthEvent.scanNonce]. */
    suspend fun record(event: GroundTruthEvent)

    /** This participant's events for one lab session — the input to zone resolution. */
    suspend fun eventsForParticipant(
        labSessionId: String,
        participantToken: String,
    ): List<GroundTruthEvent>

    /** The lab session this device has most recently scanned for, or null if it never has. */
    suspend fun lastScannedSession(participantToken: String): String?
}
