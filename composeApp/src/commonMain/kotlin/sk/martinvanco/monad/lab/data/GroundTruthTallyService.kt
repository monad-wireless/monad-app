package sk.martinvanco.monad.lab.data

import io.github.aakira.napier.Napier
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.lab.domain.GroundTruthEvent

/**
 * The room-wide ground-truth tally (`GET /api/lab/ground-truth/{labSessionId}`), and the ingest
 * call that feeds it (`POST /api/lab/ground-truth`).
 *
 * Why this exists: the console's own ground-truth count is structurally incapable of being a room
 * count. Every participant scans on their own handset, so this device's SQLite knows about one
 * person; with ten to twelve participants the local number is not a small error, it is a different
 * quantity. The room only exists on the server, where every phone's scans have been aggregated.
 *
 * Deliberately **read-mostly and failure-tolerant**. A phone associated to an experiment AP usually
 * has no route to the internet, so a failed poll is the normal state for most of a session rather
 * than an exception. Nothing here throws at the caller: [tally] returns null and the console falls
 * back to its own count with the difference labelled, because an operator who cannot tell which of
 * the two numbers they are looking at is worse off than one who only ever had the local one.
 *
 * Kept separate from [LabConfigService] rather than folded into it: that service owns a cached
 * bundle with a persisted last-good value, and this one owns a live number whose whole meaning is
 * how old it is. Caching this to disk would be actively harmful.
 *
 * Named as an interface so the flush path — where an E3 conflict is first *seen* — can be checked
 * without a server. The conflict count is the one number in this file that must never be swallowed,
 * and a rule that cannot be tested is a rule that is only believed.
 */
interface RoomTallyGateway {

    suspend fun tally(labSessionId: String, token: String?): GroundTruthTally?

    suspend fun submit(events: List<GroundTruthEvent>, token: String?): GroundTruthIngestReceipt?
}

/** The production gateway: Ktor against the backend. */
class GroundTruthTallyService : RoomTallyGateway {

    /**
     * Fetch the live tally for a session. Null on any failure — offline, unauthenticated, 5xx.
     *
     * The caller keeps the previous snapshot and ages it; a null here means "no fresh number", not
     * "zero people in the room", and the two must never be conflated on the console.
     */
    override suspend fun tally(labSessionId: String, token: String?): GroundTruthTally? {
        if (labSessionId.isBlank()) return null
        if (token.isNullOrBlank()) {
            Napier.w("[lab] tally skipped: not authenticated")
            return null
        }
        return runCatching {
            KtorClient.client.get("/api/lab/ground-truth/$labSessionId") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }.body<GroundTruthTally>()
        }.getOrElse {
            Napier.w("[lab] tally poll failed: ${it.message}")
            null
        }
    }

    /**
     * Submit scans to the server-side aggregate.
     *
     * Safe to call with rows that have already been sent: the endpoint is idempotent on
     * `scan_nonce`, which is exactly the property the whole-set re-render in
     * `GroundTruthRepository` relies on. Re-sending a session costs nothing and cannot double-count.
     *
     * Returns null on failure. Like the S3 flush, this must never be able to fail anything else:
     * the scans stay on disk and go up on the next attempt.
     */
    override suspend fun submit(events: List<GroundTruthEvent>, token: String?): GroundTruthIngestReceipt? {
        if (events.isEmpty()) return null
        if (token.isNullOrBlank()) {
            Napier.w("[lab] ground-truth submit skipped: not authenticated")
            return null
        }
        return runCatching {
            KtorClient.client.post("/api/lab/ground-truth") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                setBody(GroundTruthIngestRequest(events.map { it.toWire() }))
            }.body<GroundTruthIngestReceipt>()
        }.onSuccess { receipt ->
            if (receipt.conflicts > 0) {
                // E3. Worth a log line at the moment it happens, because by analysis time the
                // affected interval is already excluded and nobody can ask what went on.
                Napier.w("[lab] ground truth: ${receipt.conflicts} nonce conflict(s) reported by server")
            }
        }.getOrElse {
            Napier.w("[lab] ground-truth submit failed, scans retained: ${it.message}")
            null
        }
    }
}

/**
 * Wire form of one scan.
 *
 * snake_case, matching the pre-registered `ground_truth.tsv` column contract exactly — the same
 * nine names the TSV artefact and the analysis join use. Renaming one of these is a silent corpus
 * split, not a compile error, which is why they are pinned here rather than derived.
 */
@Serializable
data class GroundTruthEventWire(
    @SerialName("lab_session_id") val labSessionId: String,
    @SerialName("participant_token") val participantToken: String,
    @SerialName("zone_id") val zoneId: String,
    @SerialName("direction") val direction: String,
    @SerialName("site") val site: String,
    @SerialName("mono_ns") val monoNs: Long,
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("scan_nonce") val scanNonce: String,
    @SerialName("recording_session_id") val recordingSessionId: String? = null,
)

private fun GroundTruthEvent.toWire() = GroundTruthEventWire(
    labSessionId = labSessionId,
    participantToken = participantToken,
    zoneId = zoneId,
    direction = direction.wire,
    site = site,
    monoNs = monotonicNanos,
    wallMs = wallMillis,
    scanNonce = scanNonce,
    recordingSessionId = recordingSessionId,
)

@Serializable
data class GroundTruthIngestRequest(
    val events: List<GroundTruthEventWire>,
)

/** Per-batch outcome counts. `conflicts` is the E3 signal. */
@Serializable
data class GroundTruthIngestReceipt(
    val accepted: Int = 0,
    val duplicates: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
)

/**
 * The live room tally.
 *
 * Two counts per scope, and they are not redundant. [checkedIn] is per participant, latest scan
 * wins — idempotent if somebody taps twice. [netSum] is the literal cumulative sum of directions
 * that the pre-registration defines the occupancy level as. They agree on clean data; when they
 * do not, somebody scanned the same direction twice, and the console shows the disagreement rather
 * than picking a winner.
 */
@Serializable
data class GroundTruthTally(
    @SerialName("lab_session_id") val labSessionId: String = "",
    @SerialName("server_wall_ms") val serverWallMillis: Long = 0,
    val overall: GroundTruthOverall = GroundTruthOverall(),
    val zones: List<GroundTruthZoneTally> = emptyList(),
    val conflicts: List<GroundTruthConflictRow> = emptyList(),
)

@Serializable
data class GroundTruthOverall(
    @SerialName("checked_in") val checkedIn: Int = 0,
    /**
     * Sum of the per-zone counts. Exceeds [checkedIn] when a participant entered a new zone
     * without scanning out of the old one — a live "who forgot to scan out" indicator.
     */
    @SerialName("zone_sum") val zoneSum: Int = 0,
    @SerialName("event_count") val eventCount: Int = 0,
    @SerialName("zone_count") val zoneCount: Int = 0,
    @SerialName("last_event_wall_ms") val lastEventWallMillis: Long? = null,
    @SerialName("conflict_count") val conflictCount: Int = 0,
)

@Serializable
data class GroundTruthZoneTally(
    @SerialName("zone_id") val zoneId: String = "",
    @SerialName("checked_in") val checkedIn: Int = 0,
    @SerialName("net_sum") val netSum: Int = 0,
    @SerialName("event_count") val eventCount: Int = 0,
    @SerialName("last_event_wall_ms") val lastEventWallMillis: Long? = null,
    @SerialName("conflict_count") val conflictCount: Int = 0,
) {
    /** True when a participant scanned the same direction twice in this zone. */
    val isInconsistent: Boolean get() = checkedIn != netSum
}

@Serializable
data class GroundTruthConflictRow(
    @SerialName("scan_nonce") val scanNonce: String = "",
    @SerialName("zone_id") val zoneId: String = "",
    val accepted: String = "",
    val rejected: String = "",
)
