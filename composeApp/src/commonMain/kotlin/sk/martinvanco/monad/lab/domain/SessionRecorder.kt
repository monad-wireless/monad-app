package sk.martinvanco.monad.lab.domain

import sk.martinvanco.monad.lab.domain.health.InstrumentHealth

/**
 * Where a running session's samples go.
 *
 * The instrument is the one object in this app whose correctness is a *scientific* claim, and until
 * now it named `LabSessionRepository` — a SQLDelight class — directly, so the start-up order, the
 * gate sequence and the sidecar assembly were only readable with a database in scope.
 *
 * This port names exactly what the instrument needs of storage and nothing else: open a session,
 * append to each stream, count what was written, close it. The repository keeps its full surface
 * for the console, the uploader and recovery; those are different roles and a single "everything
 * the database can do" interface would have been a fake port.
 *
 * Deliberately narrower than [sk.martinvanco.monad.lab.data.LabSessionRepository]: nothing here can
 * delete a row, mark a session uploaded, or render a TSV. The instrument writes; releasing data is
 * the uploader's rule and stays out of reach of the measurement path.
 */
interface SessionRecorder {

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
        bootId: String,
    )

    suspend fun appendTraffic(sessionId: String, batch: List<TrafficSample>)

    suspend fun appendBeacon(sessionId: String, observation: BeaconObservation)

    suspend fun appendTransition(sessionId: String, transition: ZoneTransition)

    suspend fun appendClock(sessionId: String, estimate: ClockEstimate)

    suspend fun appendMarker(sessionId: String, marker: SessionMarker)

    suspend fun appendHealthCheckpoint(
        sessionId: String,
        monotonicNanos: Long,
        wallMillis: Long,
        health: InstrumentHealth,
    )

    suspend fun counts(sessionId: String): SessionCounts

    suspend fun close(
        sessionId: String,
        endedWallMillis: Long,
        endedMonotonicNanos: Long,
        sidecarJson: String,
    )
}

/**
 * Row counts for one session, as written into the sidecar summary.
 *
 * A domain value rather than a repository detail: the sidecar is a published artefact and these
 * numbers are part of it, so the type belongs beside the thing that publishes them.
 */
data class SessionCounts(
    val traffic: Long,
    val beacons: Long,
    val transitions: Long,
    val markers: Long = 0,
    /** `block_start` + `block_stop` rows within [markers]. */
    val blocks: Long = 0,
    val clock: Long = 0,
    val health: Long = 0,
)
