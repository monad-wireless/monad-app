package sk.martinvanco.monad.lab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.GroundTruthEventRecord
import sk.martinvanco.monad.lab.domain.GroundTruthDirection
import sk.martinvanco.monad.lab.domain.GroundTruthEvent
import sk.martinvanco.monad.lab.domain.GroundTruthStore

/**
 * Local buffer for the ground-truth (people) channel.
 *
 * A lab session runs with the app backgrounded, on a phone associated to an experiment AP that
 * usually has no route to the internet. A scan therefore has to survive on the device — possibly
 * for hours, possibly across a process death — and be sent later. That is the same shape as the
 * sample streams, so it uses the same mechanism: SQLite now, `LabSessionUploader` when there is a
 * network.
 *
 * Nothing here deletes an event. Rows are marked `uploaded` and kept: they are a few dozen per
 * session, and they are the only record in the whole system that says a *person* — not a phone —
 * was in the room.
 */
class GroundTruthRepository(
    private val database: Database,
) : GroundTruthStore {
    private val queries = database.groundTruthEventQueries

    /** Buffer one scan. Idempotent on [GroundTruthEvent.scanNonce]. */
    override suspend fun record(event: GroundTruthEvent) = withContext(Dispatchers.IO) {
        queries.insertEvent(
            labSessionId = event.labSessionId,
            recordingSessionId = event.recordingSessionId,
            participantToken = event.participantToken,
            zoneId = event.zoneId,
            direction = event.direction.wire,
            site = event.site,
            monoNs = event.monotonicNanos,
            wallMs = event.wallMillis,
            scanNonce = event.scanNonce,
        )
    }

    /**
     * This participant's last recorded direction for a zone, or null if they have never scanned it.
     *
     * The input to toggle resolution, and the reason a toggle code is safe to print: the state
     * lives with the participant who owns it, not in a shared counter that any device could race.
     */
    suspend fun lastDirection(
        labSessionId: String,
        participantToken: String,
        zoneId: String,
    ): GroundTruthDirection? = withContext(Dispatchers.IO) {
        queries.lastEventForZone(labSessionId, participantToken, zoneId)
            .executeAsOneOrNull()
            ?.let { GroundTruthDirection.fromWire(it.direction) }
    }

    suspend fun eventsForSession(labSessionId: String): List<GroundTruthEvent> =
        withContext(Dispatchers.IO) {
            queries.eventsForSession(labSessionId).executeAsList().map { it.toDomain() }
        }

    /**
     * Participants currently *inside*, by their own last scan in this session.
     *
     * The operator's sanity check against a head count. Note what it can and cannot see: it counts
     * scans recorded **on this device**. Every participant scans on their own phone, so on a
     * participant's handset this is 0 or 1, and a room-wide live tally would need the backend to
     * aggregate what has been uploaded. The number is honest about which of the two it is because
     * the console labels it as local.
     */
    suspend fun checkedIn(labSessionId: String): List<String> = withContext(Dispatchers.IO) {
        queries.eventsForSession(labSessionId).executeAsList()
            // Rows arrive in monotonic order, so the last write per participant wins.
            .associate { it.participantToken to it.direction }
            .filterValues { GroundTruthDirection.fromWire(it) == GroundTruthDirection.IN }
            .keys
            .toList()
    }

    suspend fun pendingCount(): Long = withContext(Dispatchers.IO) {
        queries.pendingCount().executeAsOne()
    }

    /**
     * Rows in one upload unit.
     *
     * The *whole* set for the pair, not the unsent part, because that is what the flush uploads —
     * a report that counted only the new rows would understate what the object now contains.
     */
    suspend fun pendingCountFor(key: GroundTruthKey): Long = withContext(Dispatchers.IO) {
        queries.countForSessionParticipant(key.labSessionId, key.participantToken).executeAsOne()
    }

    /** This participant's events for one lab session — the input to zone resolution. */
    override suspend fun eventsForParticipant(
        labSessionId: String,
        participantToken: String,
    ): List<GroundTruthEvent> = withContext(Dispatchers.IO) {
        queries.eventsForSessionParticipant(labSessionId, participantToken)
            .executeAsList()
            .map { it.toDomain() }
    }

    /**
     * The lab session this device has most recently scanned for.
     *
     * Used to notice that a code names a *different* session than the participant has been in —
     * the "wrong code" case — rather than silently switching them onto another session's truth.
     */
    override suspend fun lastScannedSession(participantToken: String): String? = withContext(Dispatchers.IO) {
        queries.lastSessionForParticipant(participantToken).executeAsOneOrNull()?.labSessionId
    }

    /** (session, participant) pairs with unsent scans. The pair is also the upload prefix. */
    suspend fun pendingKeys(): List<GroundTruthKey> = withContext(Dispatchers.IO) {
        queries.pendingKeys().executeAsList().map { GroundTruthKey(it.labSessionId, it.participantToken) }
    }

    suspend fun markUploaded(key: GroundTruthKey) = withContext(Dispatchers.IO) {
        queries.markPairUploaded(key.labSessionId, key.participantToken)
    }

    /**
     * Scans the room aggregate has not seen yet.
     *
     * Tracked apart from [pendingKeys] because the S3 artefact and the aggregate endpoint fail
     * independently. A scan can be safely in the dataset and absent from the operator's live count,
     * and the console needs to be able to say which.
     */
    suspend fun pendingIngest(): List<GroundTruthEvent> = withContext(Dispatchers.IO) {
        queries.eventsPendingIngest().executeAsList().map { it.toDomain() }
    }

    suspend fun pendingIngestCount(): Long = withContext(Dispatchers.IO) {
        queries.pendingIngestCount().executeAsOne()
    }

    /**
     * Mark exactly the scans the server acknowledged, by nonce.
     *
     * Per nonce and not per pair: a scan taken while a flush was in flight was not in the batch,
     * and marking it would strand it from the aggregate with no way to notice.
     */
    suspend fun markIngested(nonces: List<String>) = withContext(Dispatchers.IO) {
        if (nonces.isEmpty()) return@withContext
        queries.transaction {
            nonces.forEach { queries.markIngestedByNonce(it) }
        }
    }

    suspend fun sessions(): List<String> = withContext(Dispatchers.IO) {
        queries.recentSessions().executeAsList()
    }

    /**
     * The artefact.
     *
     * Tab-separated with a header row, monotonic nanoseconds first, exactly like the other four
     * streams — so a reader that already ingests `beacons.tsv` needs no new machinery. The **whole**
     * set for the pair is rendered on every flush, not just the unsent rows: the upload replaces the
     * object, so a partial render would silently truncate a session that flushed twice.
     */
    suspend fun groundTruthTsv(key: GroundTruthKey): ByteArray = withContext(Dispatchers.IO) {
        val rows = queries
            .eventsForSessionParticipant(key.labSessionId, key.participantToken)
            .executeAsList()
        buildString {
            appendLine(TSV_HEADER)
            rows.forEach {
                appendLine(
                    "${it.monoNs}\t${it.wallMs}\t${it.labSessionId}\t${it.participantToken}\t" +
                        "${it.zoneId}\t${it.direction}\t${it.site}\t${it.scanNonce}\t" +
                        (it.recordingSessionId ?: "")
                )
            }
        }.encodeToByteArray()
    }

    private fun GroundTruthEventRecord.toDomain() = GroundTruthEvent(
        labSessionId = labSessionId,
        participantToken = participantToken,
        zoneId = zoneId,
        // A row whose direction is unreadable is impossible by construction (the column is only
        // ever written from the enum), so IN is a formality rather than a fallback.
        direction = GroundTruthDirection.fromWire(direction) ?: GroundTruthDirection.IN,
        site = site,
        monotonicNanos = monoNs,
        wallMillis = wallMs,
        scanNonce = scanNonce,
        recordingSessionId = recordingSessionId,
    )

    companion object {
        const val TSV_HEADER: String =
            "mono_ns\twall_ms\tlab_session_id\tparticipant_token\tzone_id\tdirection\tsite\t" +
                "scan_nonce\trecording_session_id"
    }
}

/** A ground-truth upload unit: one session as seen by one participant. */
data class GroundTruthKey(
    val labSessionId: String,
    val participantToken: String,
)
