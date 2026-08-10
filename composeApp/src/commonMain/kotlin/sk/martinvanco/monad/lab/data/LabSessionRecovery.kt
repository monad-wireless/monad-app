package sk.martinvanco.monad.lab.data

import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.lab.domain.ClockGate
import sk.martinvanco.monad.lab.domain.ClockGateRecord
import sk.martinvanco.monad.lab.domain.ClockGateStatus
import sk.martinvanco.monad.lab.domain.LabSessionSidecar
import sk.martinvanco.monad.lab.domain.SessionEnvironment
import sk.martinvanco.monad.lab.domain.SessionEvent
import sk.martinvanco.monad.lab.domain.SessionIdentity
import sk.martinvanco.monad.lab.domain.SessionLifecycle
import sk.martinvanco.monad.lab.domain.SessionRadio
import sk.martinvanco.monad.lab.domain.SessionStatus
import sk.martinvanco.monad.lab.domain.SessionSummary
import sk.martinvanco.monad.lab.domain.clockBootId
import sk.martinvanco.monad.lab.domain.clockSourceName
import sk.martinvanco.monad.lab.domain.health.StreamState

/**
 * Closes sessions that never reached `stop()`.
 *
 * A session left `open` in the database is a recording the process did not survive: an OS kill
 * while backgrounded, a crash, or a reboot. Before this existed such a row stayed `open` forever —
 * `selectPendingUpload` only takes `closed` and `failed` — so the samples were on disk, complete,
 * and *invisible to every upload path*. The most expensive artefact in the system was the one most
 * likely to be silently stranded.
 *
 * Three rules govern the repair. The first keeps it from doing harm; the other two keep an
 * interrupted session honest rather than tidy:
 *
 * 1. **A live session is never touched.** The continuity epoch on the row is the guard — see
 *    [recover]. Without it, opening the status screen mid-recording would close the session that
 *    was running and hand it to the uploader.
 * 2. **The end is never stamped on a clock it does not belong to.** `mono_ns` resets on reboot, and
 *    on iOS is re-based on process launch. Every session that reaches recovery is from an earlier
 *    epoch, so `endedMonoNs` is left null and the sidecar records `monotonic_continuous = false`.
 *    Filling it with a reading from the current epoch would weld two timelines together in a column
 *    the pre-registration treats as the authoritative join key.
 * 3. **The truncation is written down.** The session is marked `closed` so it uploads like any
 *    other, and carries `interrupted_reason` so a reader can tell a session that ended from one
 *    that was killed, without inferring it from a missing tail.
 */
class LabSessionRecovery(
    private val repository: LabSessionRepository,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Repair every stranded session. Returns what was recovered, newest first.
     *
     * **Safe to call at any moment, including mid-recording**, and that safety is load-bearing:
     * this runs from screens a participant can open while a session is live. The guard is the
     * continuity epoch. `clockBootId()` is stable for a process lifetime and carries a per-launch
     * random component, so a row whose `bootId` equals the current epoch was opened by *this*
     * process — which means it is the session running right now, because a crash would have taken
     * the process with it. Those are skipped, unconditionally.
     *
     * A row therefore only reaches recovery if it came from an earlier epoch (or from a build
     * before epochs were recorded), and in either case its `mono_ns` is on a clock that no longer
     * exists. That is why `endedMonoNs` is never filled in here.
     */
    suspend fun recover(): List<RecoveredSession> {
        val open = repository.openSessions()
        if (open.isEmpty()) return emptyList()

        val currentEpoch = clockBootId()
        val stranded = open.filterNot { it.bootId == currentEpoch }
        if (stranded.isEmpty()) return emptyList()

        val recovered = mutableListOf<RecoveredSession>()
        stranded.forEach { record ->
            val reason = if (record.bootId == null) {
                "interrupted before this build recorded continuity epochs; monotonic continuity " +
                    "across the gap is unknown and is assumed broken"
            } else {
                "interrupted by a crash, a forced quit, or a reboot — mono_ns has been re-based " +
                    "since, so this session's end is recorded in wall time only"
            }
            val counts = repository.counts(record.sessionId)
            val checkpoint = runCatching { repository.lastHealthCheckpoint(record.sessionId) }
                .getOrNull()
            val recoveredAtWall = currentTimeMillis()
            // The last checkpoint's wall time is when the session was last observably alive. Using
            // it beats `now`, which is whenever somebody happened to reopen the app — possibly days
            // later — and would inflate the session's duration by that gap. With no checkpoint
            // there is nothing better than `now`, and the event below says which was used.
            val endedWall = checkpoint?.wallMillis ?: recoveredAtWall
            val endedMono: Long? = null

            val sidecar = LabSessionSidecar(
                identity = SessionIdentity(
                    sessionId = record.sessionId,
                    participantId = record.participantId,
                    enrollmentId = record.enrollmentId.orEmpty(),
                    questId = record.questId.orEmpty(),
                    site = record.site.orEmpty(),
                    // Roles are not recoverable from the row, and inventing them would be worse
                    // than leaving them empty: a reader can see the streams that actually have rows.
                    roles = emptyList(),
                ),
                radio = SessionRadio(
                    apId = record.apId.orEmpty(),
                    boundInterface = record.boundInterface.orEmpty(),
                    socketPinned = record.socketPinned == 1L,
                ),
                environment = SessionEnvironment(
                    // Provenance comes from the row, never from the build doing the recovery: this
                    // sidecar is written on a later launch, which may be a newer build than the one
                    // that recorded the data. `app_version` is the version embedded in the build id
                    // — see BuildIdentity.BUILD_ID for the grammar — and is empty rather than wrong
                    // when the session predates the column.
                    appVersion = record.buildId.orEmpty().substringBefore('+'),
                    buildId = record.buildId.orEmpty(),
                    clockSource = clockSourceName(),
                    bootId = record.bootId.orEmpty(),
                ),
                lifecycle = SessionLifecycle(
                    startedWallMillis = record.startedWallMs,
                    endedWallMillis = endedWall,
                    startedMonotonicNanos = record.startedMonoNs,
                    endedMonotonicNanos = endedMono ?: 0,
                    status = SessionStatus.CLOSED.storageKey,
                    events = listOfNotNull(
                        SessionEvent(
                            monotonicNanos = endedMono ?: 0,
                            wallMillis = endedWall,
                            kind = "recovered_after_interruption",
                            detail = reason,
                        ),
                        checkpoint?.let {
                            SessionEvent(
                                monotonicNanos = it.monotonicNanos,
                                wallMillis = it.wallMillis,
                                kind = "last_health_checkpoint",
                                detail = "last observed alive at this wall time; recovered at " +
                                    "$recoveredAtWall (${counts.health} checkpoints on disk)",
                            )
                        },
                    ),
                    interruptedReason = reason,
                    bootId = record.bootId.orEmpty(),
                    monotonicContinuous = false,
                ),
                summary = SessionSummary(
                    packetsSent = counts.traffic,
                    beaconObservations = counts.beacons,
                    zoneTransitions = counts.transitions,
                    markers = counts.markers,
                    blocks = counts.blocks,
                    healthCheckpoints = counts.health,
                ),
                /*
                 * Health, reconstructed from the last persisted checkpoint.
                 *
                 * Before checkpoints existed this block was simply absent on a recovered session,
                 * so the one question the health module exists for — "was it degraded, and for how
                 * long?" — was unanswerable for exactly the sessions most likely to have gone
                 * wrong. The time-in-state counters are cumulative, so the newest checkpoint alone
                 * carries the whole history the in-memory tracker held at that instant.
                 *
                 * The trailing gap between the last checkpoint and the kill is unobserved and is
                 * left that way: it is not added to any state's total, because nobody knows what
                 * the streams were doing in it.
                 */
                health = checkpoint?.streams.orEmpty(),
                // The gate is evaluated from clock.tsv by the analysis. A checkpoint carries the
                // verdict the running session had reached; without one, all this device can say is
                // how many samples survived, which is the number that decides inclusion.
                clockGate = ClockGateRecord(
                    status = checkpoint?.clockGateStatus ?: when {
                        counts.clock == 0L -> ClockGateStatus.NO_SAMPLES.wire
                        counts.clock == 1L -> ClockGateStatus.OFFSET_ONLY.wire
                        else -> ClockGateStatus.OK.wire
                    },
                    samples = counts.clock.toInt(),
                    meetsMinimumSamples = counts.clock >= ClockGate.MIN_SAMPLES_FOR_FIT,
                    maxFitResidualMillis = checkpoint?.clockResidualMillis,
                    wouldFailGate = counts.clock < ClockGate.MIN_SAMPLES_FOR_FIT ||
                        (checkpoint?.clockResidualMillis ?: 0.0) > ClockGate.BUDGET_ALL_TESTS_MILLIS,
                    note = if (checkpoint == null) {
                        "reconstructed after an interruption from clock.tsv row count; no health " +
                            "checkpoint survived the restart"
                    } else {
                        "reconstructed after an interruption from the last health checkpoint at " +
                            "mono_ns ${checkpoint.monotonicNanos}"
                    },
                ),
            )

            repository.markInterrupted(
                sessionId = record.sessionId,
                reason = reason,
                endedWallMillis = endedWall,
                endedMonotonicNanos = endedMono,
                sidecarJson = json.encodeToString(LabSessionSidecar.serializer(), sidecar),
            )
            Napier.w("[lab] recovered interrupted session ${record.sessionId.take(8)}: $reason")
            recovered += RecoveredSession(
                sessionId = record.sessionId,
                reason = reason,
                monotonicContinuous = false,
                rows = counts.traffic + counts.beacons + counts.transitions + counts.markers +
                    counts.clock + counts.health,
                startedWallMillis = record.startedWallMs,
                healthCheckpoints = counts.health,
                /*
                 * The worst state any stream reached before the process died.
                 *
                 * Null when no checkpoint survived — which is a different fact from "healthy", and
                 * the status screen says so rather than showing a reassuring blank.
                 */
                worstStreamState = checkpoint?.streams
                    ?.maxByOrNull { row ->
                        StreamState.entries.firstOrNull { it.wire == row.worst }?.severity ?: 0
                    }
                    ?.worst,
            )
        }
        return recovered.sortedByDescending { it.startedWallMillis }
    }
}

/** One session brought back from `open` and queued for upload. */
data class RecoveredSession(
    val sessionId: String,
    val reason: String,
    val monotonicContinuous: Boolean,
    val rows: Long,
    val startedWallMillis: Long,
    val healthCheckpoints: Long = 0,
    /**
     * Worst per-stream state seen before the process died, or null when no checkpoint survived.
     *
     * Null is **not** "healthy". A build with no checkpoints, or a session killed inside its first
     * thirty seconds, has nothing to report, and a screen that rendered that as a clean bill of
     * health would be inventing evidence.
     */
    val worstStreamState: String? = null,
) {
    val shortId: String get() = sessionId.take(8)

    /** True when the recovered session can answer "was it degraded, and for how long?". */
    val hasHealthHistory: Boolean get() = healthCheckpoints > 0
}
