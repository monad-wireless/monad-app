package sk.martinvanco.monad.lab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.LabSessionRecord
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.lab.domain.BeaconObservation
import sk.martinvanco.monad.lab.domain.ClockEstimate
import sk.martinvanco.monad.lab.domain.SessionStatus
import sk.martinvanco.monad.lab.domain.TrafficSample
import sk.martinvanco.monad.lab.domain.LabArtefact
import sk.martinvanco.monad.lab.domain.MeshObservation
import sk.martinvanco.monad.lab.domain.MeshSpan
import sk.martinvanco.monad.lab.domain.PoseSample
import sk.martinvanco.monad.lab.domain.PoseTrackSummary
import sk.martinvanco.monad.lab.domain.TrackingQuality
import sk.martinvanco.monad.lab.domain.SessionCounts
import sk.martinvanco.monad.lab.domain.SessionMarker
import sk.martinvanco.monad.lab.domain.SessionRecorder
import sk.martinvanco.monad.lab.domain.StreamHealthRecord
import sk.martinvanco.monad.lab.domain.ZoneTransition
import sk.martinvanco.monad.lab.domain.health.InstrumentHealth
import sk.martinvanco.monad.lab.domain.upload.PendingArtefact
import sk.martinvanco.monad.lab.domain.upload.PendingInventory
import sk.martinvanco.monad.lab.domain.upload.PendingSession

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
) : SessionRecorder {
    private val sessions = database.labSessionRecordQueries
    private val samples = database.labSampleQueries

    override suspend fun open(
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
            bootId = bootId,
            // Stamped by the *recording* build at open, not by whoever assembles the sidecar. A
            // session the OS kills is written up on a later launch that may be a different build
            // entirely; recovery reads this column rather than asking the binary it is running in.
            buildId = AppConfig.BUILD_ID,
        )
    }

    /**
     * Close a session that never reached `stop()`.
     *
     * [endedMonotonicNanos] is deliberately nullable and is expected to be null whenever the
     * recovery runs in a different continuity epoch from the one the session opened in: `mono_ns`
     * from the current epoch is not on the same timeline as the session's samples, and writing it
     * would be worse than writing nothing.
     */
    suspend fun markInterrupted(
        sessionId: String,
        reason: String,
        endedWallMillis: Long,
        endedMonotonicNanos: Long?,
        sidecarJson: String,
    ) = withContext(Dispatchers.IO) {
        sessions.markInterrupted(
            endedWallMs = endedWallMillis,
            endedMonoNs = endedMonotonicNanos,
            sidecarJson = sidecarJson,
            interruptedReason = reason,
            sessionId = sessionId,
        )
    }

    suspend fun interruptedSessions(): List<LabSessionRecord> = withContext(Dispatchers.IO) {
        sessions.selectInterrupted().executeAsList()
    }

    override suspend fun close(
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

    override suspend fun appendTraffic(sessionId: String, batch: List<TrafficSample>) =
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

    override suspend fun appendBeacon(sessionId: String, observation: BeaconObservation) =
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

    override suspend fun appendTransition(sessionId: String, transition: ZoneTransition) =
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

    /**
     * Append a batch of poses in one transaction.
     *
     * One transaction per batch, not per row: at 10 Hz a per-row commit would fsync ten times a
     * second for the whole walk, and the sampler shares a device with the write. The batch is what
     * keeps the pose stream's phase off the disk's critical path.
     */
    override suspend fun appendPose(sessionId: String, batch: List<PoseSample>) =
        withContext(Dispatchers.IO) {
            if (batch.isEmpty()) return@withContext
            samples.transaction {
                batch.forEach {
                    samples.insertPoseSample(
                        sessionId = sessionId,
                        monoNs = it.monotonicNanos,
                        wallMs = it.wallMillis,
                        x = it.x.toDouble(),
                        y = it.y.toDouble(),
                        z = it.z.toDouble(),
                        qx = it.qx.toDouble(),
                        qy = it.qy.toDouble(),
                        qz = it.qz.toDouble(),
                        qw = it.qw.toDouble(),
                        quality = it.quality.wire,
                        reason = it.reason,
                    )
                }
            }
        }

    /** Append mesh change-log rows. One transaction per batch, as for poses. */
    override suspend fun appendMesh(sessionId: String, batch: List<MeshObservation>) =
        withContext(Dispatchers.IO) {
            if (batch.isEmpty()) return@withContext
            samples.transaction {
                batch.forEach {
                    samples.insertMeshObservation(
                        sessionId = sessionId,
                        monoNs = it.monotonicNanos,
                        wallMs = it.wallMillis,
                        anchorId = it.anchorId,
                        revision = it.revision.toLong(),
                        vertices = it.vertices,
                        faces = it.faces,
                        classified = if (it.classified) 1L else 0L,
                        x = it.x.toDouble(),
                        y = it.y.toDouble(),
                        z = it.z.toDouble(),
                    )
                }
            }
        }

    override suspend fun putBlob(
        sessionId: String,
        name: String,
        contentType: String,
        monotonicNanos: Long,
        wallMillis: Long,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        samples.insertBlob(
            sessionId = sessionId,
            name = name,
            contentType = contentType,
            monoNs = monotonicNanos,
            wallMs = wallMillis,
            bytes = bytes,
            sizeBytes = bytes.size.toLong(),
        )
    }

    /** Stored artefacts for a session, **without** their bytes. See `selectBlobMetaBySession`. */
    suspend fun blobs(sessionId: String): List<StoredBlob> = withContext(Dispatchers.IO) {
        samples.selectBlobMetaBySession(sessionId).executeAsList().map {
            StoredBlob(
                name = it.name,
                contentType = it.contentType,
                monotonicNanos = it.monoNs,
                wallMillis = it.wallMs,
                sizeBytes = it.sizeBytes,
            )
        }
    }

    /** One artefact's bytes, loaded on demand — the upload path's only reason to hold a mesh. */
    suspend fun blobBytes(sessionId: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        samples.selectBlobBytes(sessionId, name).executeAsOneOrNull()
    }

    override suspend fun meshSpan(sessionId: String): MeshSpan = withContext(Dispatchers.IO) {
        val row = samples.meshSpanBySession(sessionId).executeAsOne()
        MeshSpan(
            anchors = row.anchors.toInt(),
            firstMonotonicNanos = row.firstMonoNs,
            lastMonotonicNanos = row.lastMonoNs,
        )
    }

    override suspend fun appendClock(sessionId: String, estimate: ClockEstimate) =
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

    /** Append a labelled point to the session timeline. See [SessionMarker]. */
    override suspend fun appendMarker(sessionId: String, marker: SessionMarker) =
        withContext(Dispatchers.IO) {
            samples.insertMarker(
                sessionId = sessionId,
                kind = marker.kind.wire,
                stepId = marker.stepId,
                label = marker.label,
                payload = marker.payload,
                monoNs = marker.monotonicNanos,
                wallMs = marker.wallMillis,
            )
        }

    suspend fun markers(sessionId: String): List<SessionMarker> = withContext(Dispatchers.IO) {
        samples.markersForSession(sessionId).executeAsList().map {
            SessionMarker(
                kind = SessionMarker.Kind.fromWire(it.kind),
                label = it.label,
                stepId = it.stepId,
                payload = it.payload,
                monotonicNanos = it.monoNs,
                wallMillis = it.wallMs,
            )
        }
    }

    override suspend fun counts(sessionId: String): SessionCounts = withContext(Dispatchers.IO) {
        SessionCounts(
            traffic = samples.countTrafficBySession(sessionId).executeAsOne(),
            beacons = samples.countBeaconsBySession(sessionId).executeAsOne(),
            transitions = samples.countTransitionsBySession(sessionId).executeAsOne(),
            // COUNT(*) rather than materialising the rows: a long session's marker list was being
            // loaded into memory purely to take its size, on the close path, next to the sidecar
            // render.
            markers = samples.countMarkersBySession(sessionId).executeAsOne(),
            blocks = samples.countBlockMarkersBySession(sessionId).executeAsOne(),
            waypoints = samples.countWaypointMarkersBySession(sessionId).executeAsOne(),
            clock = samples.countClockBySession(sessionId).executeAsOne(),
            health = samples.countHealthBySession(sessionId).executeAsOne(),
            pose = samples.countPoseBySession(sessionId).executeAsOne(),
            poseNormal = samples.countPoseNormalBySession(sessionId).executeAsOne(),
            mesh = samples.countMeshBySession(sessionId).executeAsOne(),
            blobs = samples.countBlobsBySession(sessionId).executeAsOne(),
            blobBytes = samples.blobBytesBySession(sessionId).executeAsOne(),
        )
    }

    /**
     * The whole pose track, reduced.
     *
     * Materialises the rows, unlike [counts] — path length is a running sum over consecutive
     * displacements and there is no way to get it from SQL aggregates. Called once, at close, on the
     * same path that renders the sidecar. A three-hour walk at 10 Hz is a hundred thousand rows of
     * twelve numbers, which is a few megabytes for one pass.
     */
    override suspend fun poseSummary(sessionId: String): PoseTrackSummary = withContext(Dispatchers.IO) {
        PoseTrackSummary.of(
            samples.selectPoseBySession(sessionId).executeAsList().map {
                PoseSample(
                    monotonicNanos = it.monoNs,
                    wallMillis = it.wallMs,
                    x = it.x.toFloat(),
                    y = it.y.toFloat(),
                    z = it.z.toFloat(),
                    qx = it.qx.toFloat(),
                    qy = it.qy.toFloat(),
                    qz = it.qz.toFloat(),
                    qw = it.qw.toFloat(),
                    quality = TrackingQuality.fromWire(it.quality),
                    reason = it.reason,
                )
            }
        )
    }

    /** `waypoint` marker rows — surveyed correspondences the trajectory fit can use. */
    suspend fun waypointCount(sessionId: String): Long = withContext(Dispatchers.IO) {
        samples.countWaypointMarkersBySession(sessionId).executeAsOne()
    }

    // ---- health checkpoints ---------------------------------------------------------------

    /**
     * Write one checkpoint: the whole health picture, one row per stream.
     *
     * Called from the instrument's existing heartbeat, throttled. It is a plain insert on the
     * heartbeat's own coroutine — it never runs inside the emission loop, and the counters it
     * records were already being polled for the display.
     */
    override suspend fun appendHealthCheckpoint(
        sessionId: String,
        monotonicNanos: Long,
        wallMillis: Long,
        health: InstrumentHealth,
    ) = withContext(Dispatchers.IO) {
        if (health.streams.isEmpty()) return@withContext
        samples.transaction {
            health.streams.forEach { stream ->
                samples.insertHealthCheckpoint(
                    sessionId = sessionId,
                    monoNs = monotonicNanos,
                    wallMs = wallMillis,
                    stream = stream.stream.name.lowercase(),
                    state = stream.state.wire,
                    worst = stream.worstState.wire,
                    events = stream.totalEvents,
                    eventsPerSecond = stream.eventsPerSecond,
                    expectedRateHz = stream.expectedRateHz,
                    silenceMs = stream.silenceMillis,
                    degradedMs = stream.millisDegraded,
                    staleMs = stream.millisStale,
                    deadMs = stream.millisDead,
                    clockGateStatus = health.clockGate.status.wire,
                    clockSamples = health.clockGate.sampleCount.toLong(),
                    clockResidualMs = health.clockGate.maxFitResidualMillis,
                )
            }
        }
    }

    /**
     * The newest checkpoint, as sidecar records.
     *
     * The time-in-state columns are cumulative, so the last checkpoint alone reconstructs the
     * history the in-memory tracker held — which is what makes "was it degraded for 42 minutes?"
     * answerable for a session that never reached `stop()`.
     */
    suspend fun lastHealthCheckpoint(sessionId: String): HealthCheckpoint? =
        withContext(Dispatchers.IO) {
            val rows = samples.selectLastHealthCheckpoint(sessionId, sessionId).executeAsList()
            if (rows.isEmpty()) return@withContext null
            val head = rows.first()
            HealthCheckpoint(
                monotonicNanos = head.monoNs,
                wallMillis = head.wallMs,
                clockGateStatus = head.clockGateStatus,
                clockSamples = head.clockSamples,
                clockResidualMillis = head.clockResidualMs,
                streams = rows.map {
                    StreamHealthRecord(
                        stream = it.stream,
                        state = it.state,
                        worst = it.worst,
                        events = it.events,
                        eventsPerSecond = it.eventsPerSecond,
                        expectedRateHz = it.expectedRateHz,
                        deliveredFraction = it.expectedRateHz
                            ?.takeIf { rate -> rate > 0.0 }
                            ?.let { rate -> it.eventsPerSecond / rate },
                        silenceMillis = it.silenceMs,
                        degradedMillis = it.degradedMs,
                        staleMillis = it.staleMs,
                        deadMillis = it.deadMs,
                    )
                },
            )
        }

    /**
     * Everything still waiting to leave this device, broken down per artefact.
     *
     * The breakdown is the point. "3 sessions pending" and "3 sessions pending — 412 000 traffic
     * rows and 2 ground-truth scans" are different facts to somebody deciding whether to walk into
     * Wi-Fi range now or after lunch.
     */
    suspend fun pendingInventory(
        groundTruthRows: Long = 0,
        groundTruthNotInTally: Long = 0,
    ): PendingInventory =
        withContext(Dispatchers.IO) {
            val rows = sessions.selectPendingUpload().executeAsList().map { record ->
                PendingSession(
                    sessionId = record.sessionId,
                    status = record.status,
                    startedWallMillis = record.startedWallMs,
                    uploadError = record.uploadError,
                    artefacts = listOf(
                        PendingArtefact(
                            LabArtefact.TRAFFIC,
                            samples.countTrafficBySession(record.sessionId).executeAsOne(),
                        ),
                        PendingArtefact(
                            LabArtefact.BEACONS,
                            samples.countBeaconsBySession(record.sessionId).executeAsOne(),
                        ),
                        PendingArtefact(
                            LabArtefact.TRANSITIONS,
                            samples.countTransitionsBySession(record.sessionId).executeAsOne(),
                        ),
                        PendingArtefact(
                            LabArtefact.CLOCK,
                            samples.countClockBySession(record.sessionId).executeAsOne(),
                        ),
                        PendingArtefact(
                            LabArtefact.MARKERS,
                            samples.countMarkersBySession(record.sessionId).executeAsOne(),
                        ),
                        PendingArtefact(
                            LabArtefact.HEALTH,
                            samples.countHealthBySession(record.sessionId).executeAsOne(),
                        ),
                        PendingArtefact(
                            LabArtefact.POSE,
                            samples.countPoseBySession(record.sessionId).executeAsOne(),
                        ),
                        PendingArtefact(
                            LabArtefact.MESH_LOG,
                            samples.countMeshBySession(record.sessionId).executeAsOne(),
                        ),
                    ),
                )
            }
            PendingInventory(
                sessions = rows,
                groundTruthRows = groundTruthRows,
                groundTruthNotInTally = groundTruthNotInTally,
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
    /**
     * Step boundaries as a stream, on the same monotonic clock as the samples.
     *
     * ``payload`` is the step's config JSON verbatim — occupancy, arrangement, posture — kept as
     * one opaque column on purpose: a quest can add a field without this exporter, the schema, or
     * the reader needing to change.
     */
    suspend fun markersTsv(sessionId: String): ByteArray = withContext(Dispatchers.IO) {
        val rows = samples.markersForSession(sessionId).executeAsList()
        buildString {
            appendLine("mono_ns\twall_ms\tkind\tstep_id\tlabel\tpayload_json")
            rows.forEach {
                // Tabs and newlines inside a JSON payload would break the row; escaped rather than
                // stripped so the payload stays parseable on the far side.
                val payload = (it.payload ?: "")
                    .replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
                appendLine(
                    "${it.monoNs}\t${it.wallMs}\t${it.kind}\t${it.stepId ?: ""}\t" +
                        "${it.label}\t$payload"
                )
            }
        }.encodeToByteArray()
    }

    /**
     * Per-stream liveness through the session, on the same monotonic clock as the samples.
     *
     * One row per stream per checkpoint. `degraded_ms` / `stale_ms` / `dead_ms` are cumulative, so
     * a reader can either take the last row (the whole-session figure) or difference consecutive
     * rows to find *which* windows were bad.
     */
    suspend fun healthTsv(sessionId: String): ByteArray = withContext(Dispatchers.IO) {
        val rows = samples.selectHealthBySession(sessionId).executeAsList()
        buildString {
            appendLine(
                "mono_ns\twall_ms\tstream\tstate\tworst\tevents\tevents_per_second\t" +
                    "expected_rate_hz\tsilence_ms\tdegraded_ms\tstale_ms\tdead_ms\t" +
                    "clock_gate_status\tclock_samples\tclock_residual_ms"
            )
            rows.forEach {
                appendLine(
                    "${it.monoNs}\t${it.wallMs}\t${it.stream}\t${it.state}\t${it.worst}\t" +
                        "${it.events}\t${it.eventsPerSecond}\t${it.expectedRateHz ?: ""}\t" +
                        "${it.silenceMs}\t${it.degradedMs}\t${it.staleMs}\t${it.deadMs}\t" +
                        "${it.clockGateStatus}\t${it.clockSamples}\t${it.clockResidualMs ?: ""}"
                )
            }
        }.encodeToByteArray()
    }

    /**
     * The pose track as TSV.
     *
     * Unrounded, deliberately. Rounding to millimetres would be defensible for the position and
     * wrong for the quaternion, where a truncated component is a rotation error that grows with
     * distance from the origin. Kotlin's default rendering round-trips a double exactly, so the text
     * carries what was stored and no reader has to know the renderer's precision policy.
     *
     * `quality` and `reason` are columns rather than a filtered-out prefix: the analysis has to be
     * able to drop the untrusted windows itself, and a renderer that dropped them here would leave a
     * track with unexplained gaps.
     */
    suspend fun poseTsv(sessionId: String): ByteArray = withContext(Dispatchers.IO) {
        val rows = samples.selectPoseBySession(sessionId).executeAsList()
        buildString {
            appendLine("mono_ns\twall_ms\tx_m\ty_m\tz_m\tqx\tqy\tqz\tqw\tquality\treason")
            rows.forEach {
                appendLine(
                    "${it.monoNs}\t${it.wallMs}\t${it.x}\t${it.y}\t${it.z}\t" +
                        "${it.qx}\t${it.qy}\t${it.qz}\t${it.qw}\t" +
                        "${it.quality}\t${it.reason ?: ""}"
                )
            }
        }.encodeToByteArray()
    }

    /**
     * The mesh change log as TSV.
     *
     * `mono_ns` first, like every other stream, because that is the column the analysis joins on — and
     * joining is the entire purpose of this file. The geometry lives in `mesh.ply`; this says *when*
     * each block became what that file contains, which is what lets a mesh be placed on a CSI window
     * instead of merely accompanying it.
     */
    suspend fun meshTsv(sessionId: String): ByteArray = withContext(Dispatchers.IO) {
        val rows = samples.selectMeshBySession(sessionId).executeAsList()
        buildString {
            appendLine(
                "mono_ns\twall_ms\tanchor_id\trevision\tvertices\tfaces\tclassified\t" +
                    "x_m\ty_m\tz_m"
            )
            rows.forEach {
                appendLine(
                    "${it.monoNs}\t${it.wallMs}\t${it.anchorId}\t${it.revision}\t" +
                        "${it.vertices}\t${it.faces}\t${it.classified}\t" +
                        "${it.x}\t${it.y}\t${it.z}"
                )
            }
        }.encodeToByteArray()
    }

    suspend fun purgeUploaded(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val record = sessions.selectById(sessionId).executeAsOneOrNull() ?: return@withContext false
        if (SessionStatus.fromStorage(record.status) != SessionStatus.UPLOADED) return@withContext false
        samples.transaction {
            samples.deleteTrafficBySession(sessionId)
            samples.deleteBeaconsBySession(sessionId)
            samples.deleteTransitionsBySession(sessionId)
            samples.deleteClockBySession(sessionId)
            samples.deleteMarkersForSession(sessionId)
            samples.deleteHealthBySession(sessionId)
            samples.deletePoseBySession(sessionId)
            samples.deleteMeshBySession(sessionId)
            samples.deleteBlobsBySession(sessionId)
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
            samples.deleteMarkersForSession(sessionId)
            samples.deleteHealthBySession(sessionId)
            samples.deletePoseBySession(sessionId)
            samples.deleteMeshBySession(sessionId)
            samples.deleteBlobsBySession(sessionId)
        }
        sessions.deleteSession(sessionId)
    }
}

/**
 * The newest persisted health checkpoint of a session.
 *
 * Exists so a session that never reached `stop()` still has a health story. Without it, recovery
 * could reconstruct row counts and nothing about *when* those rows stopped keeping pace.
 */
data class HealthCheckpoint(
    val monotonicNanos: Long,
    val wallMillis: Long,
    val clockGateStatus: String,
    val clockSamples: Long,
    val clockResidualMillis: Double?,
    val streams: List<StreamHealthRecord>,
)

/**
 * A stored binary artefact, described without loading it.
 *
 * The size matters on its own: a mesh is the largest thing this app uploads by an order of magnitude,
 * and an operator deciding whether to upload now or after lunch is deciding about megabytes rather than
 * about row counts.
 */
data class StoredBlob(
    val name: String,
    val contentType: String,
    val monotonicNanos: Long,
    val wallMillis: Long,
    val sizeBytes: Long,
)
