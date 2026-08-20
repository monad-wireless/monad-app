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

    /**
     * Append a batch of poses.
     *
     * Batched, unlike every other append here, because this stream is paced rather than
     * event-driven: a per-sample write would put a SQLite transaction inside the sampler's period
     * and a slow write would then shift the *next* pose's timestamp. The instrument buffers and
     * flushes, exactly as it does for traffic.
     */
    suspend fun appendPose(sessionId: String, batch: List<PoseSample>)

    /** Append mesh change-log rows. Empty batches are a no-op, which is the common case. */
    suspend fun appendMesh(sessionId: String, batch: List<MeshObservation>)

    /**
     * Store a binary artefact under [name], replacing any previous one with that name.
     *
     * Replacing rather than appending: two artefacts under one name would make the uploaded prefix
     * depend on row order, and a re-export is a correction, not a second observation.
     */
    suspend fun putBlob(
        sessionId: String,
        name: String,
        contentType: String,
        monotonicNanos: Long,
        wallMillis: Long,
        bytes: ByteArray,
    )

    suspend fun appendMarker(sessionId: String, marker: SessionMarker)

    /**
     * Read a stored blob back. The one read on this port, and it exists for the site map: a walk
     * that starts by loading the site's saved world map is a walk whose poses come out in the same
     * frame as every previous walk on that site.
     */
    suspend fun getBlob(sessionId: String, name: String): ByteArray?

    /**
     * Append instrument log lines.
     *
     * Batched like poses, for a different reason: log lines are written from `note()`, which is not
     * suspend and must not become suspend — it is called beside the sampling paths. The instrument
     * buffers and the heartbeat flushes, so a line costs a list append at the call site.
     */
    suspend fun appendLog(sessionId: String, batch: List<InstrumentLogEntry>)

    suspend fun appendHealthCheckpoint(
        sessionId: String,
        monotonicNanos: Long,
        wallMillis: Long,
        health: InstrumentHealth,
    )

    suspend fun counts(sessionId: String): SessionCounts

    /**
     * The pose track, reduced — path length, trusted fraction, extent.
     *
     * A read of what was just written, on the same footing as [counts], and here rather than in the
     * instrument for one reason: **recovery has to produce the same numbers**. A session the OS
     * killed gets its sidecar assembled by a later launch that never saw the poses stream past, so a
     * summary accumulated in memory would exist for clean stops and be zero for crashes — and zero
     * path length reads as "the walk went nowhere", which is the wrong sentence for the sessions most
     * likely to have gone wrong. One reduction over the stored rows serves both paths.
     */
    suspend fun poseSummary(sessionId: String): PoseTrackSummary

    /**
     * When the geometry was seen, and how many distinct blocks.
     *
     * Read back rather than accumulated in the instrument for the same reason as [poseSummary]: a
     * recovered session's sidecar is assembled by a later launch that never watched the walk, and a
     * window of `null…null` on a session that logged geometry would be a false statement about
     * provenance rather than a missing one.
     */
    suspend fun meshSpan(sessionId: String): MeshSpan

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
    /**
     * `waypoint` rows within [markers] — surveyed correspondences for the trajectory fit.
     *
     * Three is the floor for recovering the site-frame transform *and* bounding the drift between
     * them. A walk with fewer has a shape and no place, which is a different dataset.
     */
    val waypoints: Long = 0,
    val clock: Long = 0,
    val health: Long = 0,
    /** Rows in `pose.tsv`. */
    val pose: Long = 0,
    /** Rows in `mesh.tsv` — geometry changes logged, not blocks. */
    val mesh: Long = 0,
    /** Rows in `log.tsv` — what the instrument said while the session ran. */
    val log: Long = 0,
    /** Stored binary artefacts, and their total size. */
    val blobs: Long = 0,
    val blobBytes: Long = 0,
    /**
     * Of [pose], how many the platform called `normal`.
     *
     * Counted here rather than derived at close so a **recovered** session can still report the
     * trust fraction of its track. A walk that was 12 % trusted has to be re-taken, and a session
     * the OS killed is not a random sample of sessions.
     */
    val poseNormal: Long = 0,
)

/**
 * The window the mesh was observed in, and how many distinct blocks were seen.
 *
 * `null` bounds mean no geometry was ever logged — which is a different fact from a zero-length window,
 * and the sidecar has to be able to say which.
 */
data class MeshSpan(
    val anchors: Int = 0,
    val firstMonotonicNanos: Long? = null,
    val lastMonotonicNanos: Long? = null,
) {
    companion object {
        val NONE = MeshSpan()
    }
}
