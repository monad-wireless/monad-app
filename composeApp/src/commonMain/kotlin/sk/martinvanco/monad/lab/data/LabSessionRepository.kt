package sk.martinvanco.monad.lab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.LabSessionRecord
import sk.martinvanco.monad.lab.domain.BeaconObservation
import sk.martinvanco.monad.lab.domain.ClockEstimate
import sk.martinvanco.monad.lab.domain.SessionStatus
import sk.martinvanco.monad.lab.domain.TrafficSample
import sk.martinvanco.monad.lab.domain.ZoneTransition

/**
 * Local store for lab sessions and their sample streams.
 *
 * The deletion rules are the point of this class. Nothing here deletes a session's samples except
 * [purgeUploaded], and that only touches rows whose status is `uploaded` — i.e. the server has
 * acknowledged every artefact. The app's older quest path deleted local data on completion
 * regardless of upload outcome, which discards the only copy when an upload fails; that mistake is
 * not repeated here.
 */
class LabSessionRepository(
    private val database: Database,
) {
    private val sessions = database.labSessionRecordQueries
    private val samples = database.labSampleQueries

    suspend fun open(
        sessionId: String,
        participantId: String,
        enrollmentId: String?,
        questId: String?,
        site: String?,
        apId: String?,
        profileId: String?,
        startedWallMillis: Long,
        startedMonotonicNanos: Long,
        boundInterface: String?,
        socketPinned: Boolean,
    ) = withContext(Dispatchers.IO) {
        sessions.insertSession(
            sessionId = sessionId,
            participantId = participantId,
            enrollmentId = enrollmentId,
            questId = questId,
            site = site,
            apId = apId,
            profileId = profileId,
            startedWallMs = startedWallMillis,
            startedMonoNs = startedMonotonicNanos,
            boundInterface = boundInterface,
            socketPinned = if (socketPinned) 1L else 0L,
        )
    }

    suspend fun close(
        sessionId: String,
        endedWallMillis: Long,
        endedMonotonicNanos: Long,
        sidecarJson: String,
    ) = withContext(Dispatchers.IO) {
        sessions.closeSession(endedWallMillis, endedMonotonicNanos, sidecarJson, sessionId)
    }

    suspend fun updateBinding(sessionId: String, description: String, pinned: Boolean) =
        withContext(Dispatchers.IO) {
            sessions.updateBinding(description, if (pinned) 1L else 0L, sessionId)
        }

    suspend fun markUploaded(sessionId: String) = withContext(Dispatchers.IO) {
        sessions.markUploaded(sessionId)
    }

    suspend fun markFailed(sessionId: String, error: String) = withContext(Dispatchers.IO) {
        sessions.markFailed(error, sessionId)
    }

    suspend fun all(): List<LabSessionRecord> = withContext(Dispatchers.IO) {
        sessions.selectAll().executeAsList()
    }

    suspend fun byId(sessionId: String): LabSessionRecord? = withContext(Dispatchers.IO) {
        sessions.selectById(sessionId).executeAsOneOrNull()
    }

    suspend fun pendingUpload(): List<LabSessionRecord> = withContext(Dispatchers.IO) {
        sessions.selectPendingUpload().executeAsList()
    }

    suspend fun openSessions(): List<LabSessionRecord> = withContext(Dispatchers.IO) {
        sessions.selectOpen().executeAsList()
    }

    suspend fun unsyncedCount(): Long = withContext(Dispatchers.IO) {
        sessions.countUnsynced().executeAsOne()
    }

    // ---- sample streams -----------------------------------------------------------------

    suspend fun appendTraffic(sessionId: String, batch: List<TrafficSample>) =
        withContext(Dispatchers.IO) {
            if (batch.isEmpty()) return@withContext
            samples.transaction {
                batch.forEach {
                    samples.insertTrafficSample(
                        sessionId = sessionId,
                        sequence = it.sequence.toLong(),
                        sentMonoNs = it.sentAtMonotonicNanos,
                        latenessNs = it.latenessNanos,
                        sizeBytes = it.sizeBytes.toLong(),
                    )
                }
            }
        }

    suspend fun appendBeacon(sessionId: String, observation: BeaconObservation) =
        withContext(Dispatchers.IO) {
            samples.insertBeaconObservation(
                sessionId = sessionId,
                uuid = observation.uuid,
                major = observation.major.toLong(),
                minor = observation.minor.toLong(),
                rssi = observation.rssi.toLong(),
                txPower = observation.txPower?.toLong(),
                proximity = observation.proximity,
                accuracyM = observation.accuracyMetres,
                monoNs = observation.monotonicNanos,
                wallMs = observation.wallMillis,
            )
        }

    suspend fun appendTransition(sessionId: String, transition: ZoneTransition) =
        withContext(Dispatchers.IO) {
            samples.insertZoneTransition(
                sessionId = sessionId,
                major = transition.major.toLong(),
                minor = transition.minor.toLong(),
                cellId = transition.zone?.cellId,
                label = transition.zone?.label,
                entered = if (transition.entered) 1L else 0L,
                monoNs = transition.monotonicNanos,
                wallMs = transition.wallMillis,
            )
        }

    suspend fun appendClock(sessionId: String, estimate: ClockEstimate) =
        withContext(Dispatchers.IO) {
            samples.insertClockSample(
                sessionId = sessionId,
                monoNs = estimate.anchorNanos,
                offsetNs = estimate.offsetNanos,
                rttNs = estimate.delayNanos,
                skewPpm = estimate.skewPpm,
                samples = estimate.samples.toLong(),
            )
        }

    suspend fun counts(sessionId: String): SessionCounts = withContext(Dispatchers.IO) {
        SessionCounts(
            traffic = samples.countTrafficBySession(sessionId).executeAsOne(),
            beacons = samples.countBeaconsBySession(sessionId).executeAsOne(),
            transitions = samples.countTransitionsBySession(sessionId).executeAsOne(),
        )
    }

    // ---- export -------------------------------------------------------------------------

    /**
     * TSV renderers. Tab-separated with a header row, matching the format the collection side
     * already ingests, and with **monotonic** nanoseconds as the primary time column — the wall
     * clock is carried alongside but is never the join key.
     */
    suspend fun trafficTsv(sessionId: String): ByteArray = withContext(Dispatchers.IO) {
        val rows = samples.selectTrafficBySession(sessionId).executeAsList()
        buildString {
            appendLine("sequence\tsent_mono_ns\tlateness_ns\tsize_bytes")
            rows.forEach { appendLine("${it.sequence}\t${it.sentMonoNs}\t${it.latenessNs}\t${it.sizeBytes}") }
        }.encodeToByteArray()
    }

    suspend fun beaconsTsv(sessionId: String): ByteArray = withContext(Dispatchers.IO) {
        val rows = samples.selectBeaconsBySession(sessionId).executeAsList()
        buildString {
            appendLine("mono_ns\twall_ms\tuuid\tmajor\tminor\trssi\ttx_power\tproximity\taccuracy_m")
            rows.forEach {
                appendLine(
                    "${it.monoNs}\t${it.wallMs}\t${it.uuid}\t${it.major}\t${it.minor}\t${it.rssi}\t" +
                        "${it.txPower ?: ""}\t${it.proximity ?: ""}\t${it.accuracyM ?: ""}"
                )
            }
        }.encodeToByteArray()
    }

    suspend fun transitionsTsv(sessionId: String): ByteArray = withContext(Dispatchers.IO) {
        val rows = samples.selectTransitionsBySession(sessionId).executeAsList()
        buildString {
            appendLine("mono_ns\twall_ms\tmajor\tminor\tcell_id\tlabel\tentered")
            rows.forEach {
                appendLine(
                    "${it.monoNs}\t${it.wallMs}\t${it.major}\t${it.minor}\t${it.cellId ?: ""}\t" +
                        "${it.label ?: ""}\t${it.entered}"
                )
            }
        }.encodeToByteArray()
    }

    suspend fun clockTsv(sessionId: String): ByteArray = withContext(Dispatchers.IO) {
        val rows = samples.selectClockBySession(sessionId).executeAsList()
        buildString {
            appendLine("mono_ns\toffset_ns\trtt_ns\tskew_ppm\tsamples")
            rows.forEach {
                appendLine("${it.monoNs}\t${it.offsetNs}\t${it.rttNs}\t${it.skewPpm}\t${it.samples}")
            }
        }.encodeToByteArray()
    }

    /**
     * Delete a session and its samples. Refuses unless the session is already `uploaded` — the one
     * rule this whole class exists to enforce.
     */
    suspend fun purgeUploaded(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val record = sessions.selectById(sessionId).executeAsOneOrNull() ?: return@withContext false
        if (SessionStatus.fromStorage(record.status) != SessionStatus.UPLOADED) return@withContext false
        samples.transaction {
            samples.deleteTrafficBySession(sessionId)
            samples.deleteBeaconsBySession(sessionId)
            samples.deleteTransitionsBySession(sessionId)
            samples.deleteClockBySession(sessionId)
        }
        sessions.deleteSession(sessionId)
        true
    }

    /** Operator escape hatch for the debug console: drop a session even if unsynced. */
    suspend fun forceDelete(sessionId: String) = withContext(Dispatchers.IO) {
        samples.transaction {
            samples.deleteTrafficBySession(sessionId)
            samples.deleteBeaconsBySession(sessionId)
            samples.deleteTransitionsBySession(sessionId)
            samples.deleteClockBySession(sessionId)
        }
        sessions.deleteSession(sessionId)
    }
}

data class SessionCounts(
    val traffic: Long,
    val beacons: Long,
    val transitions: Long,
)
