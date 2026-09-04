@file:OptIn(ExperimentalSerializationApi::class)

package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The session sidecar — the phone's answer to `csid`'s CSIQ session block.
 *
 * A wireless measurement without recorded provenance is not comparable to any other measurement,
 * and a phone is the most device-dependent radio in the room: OS version, power state, chipset,
 * and which interface the socket actually bound to all change what the observer sees. The sidecar
 * exists so that a phone session can sit alongside `csid:<host>/<session>` as a first-class source
 * rather than as a mystery TSV.
 *
 * Group layout deliberately mirrors CSIQ v1: identity / radio / environment / lifecycle / summary.
 */
@Serializable
data class LabSessionSidecar(
    @SerialName("schema") val schema: String = SCHEMA,
    val identity: SessionIdentity,
    val radio: SessionRadio,
    val environment: SessionEnvironment,
    val lifecycle: SessionLifecycle,
    val summary: SessionSummary,
    @SerialName("clock_samples") val clockSamples: List<ClockSampleRecord> = emptyList(),
    /**
     * Per-stream liveness over the whole session — including **how long** each stream spent
     * degraded, stale or dead.
     *
     * A final-value-only report is not enough. The capture that motivated this block ran to
     * completion and looked healthy at the end; it had spent 42 minutes at 11.6 % delivery in the
     * middle. Time-in-state is what makes that visible in the sidecar rather than in analysis.
     */
    val health: List<StreamHealthRecord> = emptyList(),
    /** Gate G4 as this device could evaluate it. See `ClockGate`. */
    @SerialName("clock_gate") val clockGate: ClockGateRecord? = null,
) {
    companion object {
        /**
         * v2 added `health` and `clock_gate`. Both are additive with defaults, so a v1 reader still
         * parses a v2 sidecar; the version was bumped anyway because a reader that *needs* the
         * health block must be able to tell whether its absence means "healthy" or "old app".
         *
         * v3 adds `summary.blocks` and `summary.health_checkpoints`, and — the reason it is a
         * version and not a silent addition — changes what `health` means on a **recovered**
         * session. In v2 a recovered sidecar carried no health at all; in v3 it carries the last
         * persisted checkpoint. A reader must be able to tell "this session was healthy" from
         * "this build could not say", and only the version distinguishes them.
         *
         * v4 adds `environment.build_id` and changes what `environment.app_version` *means*. Up to
         * v3 it carried a hand-maintained string (`0.3.0-lab`) that neither Gradle nor Xcode used,
         * so it identified nothing; from v4 it is the marketing version of the actual build, and
         * `build_id` identifies the binary. A reader must not treat a v3 `app_version` and a v4
         * `app_version` as the same kind of value, which is exactly what the version bump says.
         *
         * v5 is the walk: `summary.pose_track`, `summary.pose_tracker`, `summary.waypoints` and
         * `summary.mesh`, alongside three new artefacts (`pose.tsv`, `mesh.tsv`, `mesh.ply`). The version
         * is bumped rather than the fields quietly added because their **absence changes meaning**: a v5
         * sidecar with `pose_track = null` says the session deliberately recorded no trajectory, while a
         * v4 sidecar says nothing at all about trajectories because the build could not produce one. A
         * reader that treats the two alike counts every pre-v5 walk as a walk that failed to track.
         *
         * One version rather than two for the trajectory and the geometry, because they are one fact:
         * `mesh.ply` is in the frame `pose.tsv` defines and `mesh.tsv` is on the clock `pose.tsv` uses, so
         * a reader that can handle one and not the other cannot use either.
         */
        const val SCHEMA = "monad-app/session-sidecar/v5"
    }
}

/** One stream's liveness record. Field names mirror the health model's wire names. */
@Serializable
data class StreamHealthRecord(
    val stream: String,
    /** State at close. */
    val state: String,
    /** Worst state reached at any point — the field that cannot be un-seen. */
    val worst: String,
    val events: Long = 0,
    @SerialName("events_per_second") val eventsPerSecond: Double = 0.0,
    @SerialName("expected_rate_hz") val expectedRateHz: Double? = null,
    @SerialName("delivered_fraction") val deliveredFraction: Double? = null,
    @SerialName("silence_ms") val silenceMillis: Long = 0,
    @SerialName("degraded_ms") val degradedMillis: Long = 0,
    @SerialName("stale_ms") val staleMillis: Long = 0,
    @SerialName("dead_ms") val deadMillis: Long = 0,
)

/**
 * Gate G4 as evaluated on-device.
 *
 * `max_fit_residual_ms` is the residual of this phone's own sync samples against their own affine
 * fit — **not** the registered marker-vs-CSI residual, which needs the fleet side. Named `fit` so
 * the distinction survives into the dataset.
 */
@Serializable
data class ClockGateRecord(
    val status: String,
    val samples: Int,
    @SerialName("meets_minimum_samples") val meetsMinimumSamples: Boolean,
    @SerialName("skew_ppm") val skewPpm: Double = 0.0,
    @SerialName("offset_ms") val offsetMillis: Double = 0.0,
    /** `a` of `unix_ts_ns ≈ a·mono_ns + b`, as the device would fit it. */
    @SerialName("fit_a") val fitA: Double? = null,
    @SerialName("fit_b_ns") val fitBNanos: Double? = null,
    @SerialName("fit_span_ms") val fitSpanMillis: Double? = null,
    @SerialName("max_fit_residual_ms") val maxFitResidualMillis: Double? = null,
    @SerialName("would_fail_gate") val wouldFailGate: Boolean = false,
    val note: String = "",
)

@Serializable
data class SessionIdentity(
    @SerialName("session_id") val sessionId: String,
    /** Pseudonymous participant key. Never the account e-mail — the game owns the account, the
     *  dataset owns only the pseudonym. */
    @SerialName("participant_id") val participantId: String,
    @SerialName("enrollment_id") val enrollmentId: String = "",
    @SerialName("quest_id") val questId: String = "",
    val site: String = "",
    @SerialName("config_version") val configVersion: Int = 0,
    /** Roles this device played in the session. */
    val roles: List<String> = emptyList(),
)

@Serializable
data class SessionRadio(
    @SerialName("ap_id") val apId: String = "",
    val ssid: String = "",
    val band: String = "",
    val channel: Int? = null,
    @SerialName("collector_host") val collectorHost: String = "",
    @SerialName("collector_port") val collectorPort: Int = 0,
    /** What the socket actually pinned to — the field that distinguishes a real run from one that
     *  silently left over cellular. */
    @SerialName("bound_interface") val boundInterface: String = "",
    @SerialName("socket_pinned") val socketPinned: Boolean = false,
    @SerialName("beacon_uuid") val beaconUuid: String = "",
    /** What the identity broadcast actually put on air; empty/null when the role never ran. */
    @SerialName("advertise_uuid") val advertiseUuid: String = "",
    @SerialName("advertise_interval") val advertiseInterval: String = "",
    @SerialName("advertise_tx_power") val advertiseTxPower: String = "",
    @SerialName("advertise_foreground_only") val advertiseForegroundOnly: Boolean? = null,
)

@Serializable
data class SessionEnvironment(
    val platform: String = "",
    @SerialName("os_version") val osVersion: String = "",
    @SerialName("device_model") val deviceModel: String = "",
    /** Marketing version, e.g. `1.2.0`. Human-facing; [buildId] is what identifies the build. */
    @SerialName("app_version") val appVersion: String = "",
    /**
     * The build that produced this recording.
     *
     * `<version>+<versionCode>.g<commit8>[.dirty<worktree8>]`, e.g.
     * `1.2.0+5.g0940fc0d.dirty7f3a91c2`. Two builds of the same version made from different commits
     * — or from the same commit with different uncommitted changes — differ here, which is the
     * whole point: `app_version` alone could never answer "which binary recorded this?".
     *
     * Empty only on a session recorded by a build that predates this field.
     */
    @SerialName("build_id") val buildId: String = "",
    @SerialName("clock_source") val clockSource: String = "",
    @SerialName("boot_id") val bootId: String = "",
    val timezone: String = "",
    /** Residency checks as they stood at session start — so a suspicious session can be explained
     *  rather than argued about. */
    @SerialName("residency_checks") val residencyChecks: List<String> = emptyList(),
    /**
     * The hardware identifier (`iPhone15,2`, `a54x`) and the OS build (`22G86`) — IP-149. Flat
     * copies of two descriptor fields, because these are the two a reader filters on and a reader
     * should not have to open the block below to do it. Empty on a session recorded by a build that
     * predates them.
     */
    val machine: String = "",
    @SerialName("os_build") val osBuild: String = "",
    /**
     * The whole handset descriptor as sent at quest start — IP-149, owner decision Q2: the dataset
     * on S3 is self-describing, so an analysis conditioning on the IMU vendor or the BLE PHY set
     * reads the sidecar and never needs the backend. The same bytes the backend froze on the
     * enrollment, so the two can be compared. Null on a session no quest started (the lab console)
     * or a build that predates the descriptor.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val handset: HandsetDescriptor? = null,
)

@Serializable
data class SessionLifecycle(
    @SerialName("started_wall_ms") val startedWallMillis: Long = 0,
    @SerialName("ended_wall_ms") val endedWallMillis: Long = 0,
    @SerialName("started_mono_ns") val startedMonotonicNanos: Long = 0,
    @SerialName("ended_mono_ns") val endedMonotonicNanos: Long = 0,
    val status: String = "open",
    /** Interface changes, backgrounding, residency loss — anything that breaks an assumption. */
    val events: List<SessionEvent> = emptyList(),
    /**
     * Non-null when the session did not end through `stop()` — a crash, a kill, or a reboot.
     *
     * Such a session is still uploaded: a truncated recording is evidence. It is marked so that
     * nobody reads its missing tail as a quiet session.
     */
    @SerialName("interrupted_reason") val interruptedReason: String? = null,
    /**
     * The continuity epoch the session started in. `mono_ns` is only comparable inside one epoch —
     * it resets on reboot — so a session whose epoch differs from the current one can never have
     * its end stamped on the same monotonic timeline as its start.
     */
    @SerialName("boot_id") val bootId: String = "",
    /**
     * False when the end of the session was recorded in a **different** continuity epoch from its
     * start, which makes `ended_mono_ns` meaningless. It is then left at 0 rather than filled with
     * a number from the wrong clock.
     */
    @SerialName("monotonic_continuous") val monotonicContinuous: Boolean = true,
)

@Serializable
data class SessionEvent(
    @SerialName("mono_ns") val monotonicNanos: Long,
    @SerialName("wall_ms") val wallMillis: Long,
    val kind: String,
    val detail: String = "",
)

@Serializable
data class SessionSummary(
    @SerialName("commanded_rate_hz") val commandedRateHz: Double = 0.0,
    @SerialName("achieved_rate_hz") val achievedRateHz: Double = 0.0,
    @SerialName("packets_sent") val packetsSent: Long = 0,
    @SerialName("packets_failed") val packetsFailed: Long = 0,
    @SerialName("interval_cv") val intervalCv: Double = 0.0,
    @SerialName("max_gap_ms") val maxGapMillis: Double = 0.0,
    @SerialName("beacon_observations") val beaconObservations: Long = 0,
    @SerialName("zone_transitions") val zoneTransitions: Long = 0,
    /**
     * Step markers written during the run. A reader that sees zero here knows the session is a
     * continuous recording with no take structure — which is a legitimate session (a bare lab
     * console run) but not a usable one for a labelled experiment, and the difference should not
     * require downloading the stream to discover.
     */
    @SerialName("markers") val markers: Long = 0,
    /**
     * Block edges written (`block_start` + `block_stop` rows).
     *
     * Zero on a session that ran the frozen design means the operator never labelled a block, and
     * the analysis would have to infer block boundaries from the count trace — which is fragile at
     * exactly the cycling-ramp edges T3 depends on. Surfaced here so that is knowable without
     * downloading `markers.tsv`.
     */
    @SerialName("blocks") val blocks: Long = 0,
    /**
     * True when a block was still open when the session ended, and was therefore closed by the
     * instrument rather than by the operator.
     *
     * At most one block can be open at a time, so this is a fact and not a count. It matters
     * because the trailing edge of such a block is an artefact of the session stopping, not a
     * judgement that the condition was over — recoverable (the edge exists, and carries
     * `stop_reason=session_end`), but the block's duration must be read with that in mind.
     */
    @SerialName("block_open_at_session_end") val blockOpenAtSessionEnd: Boolean = false,
    /** Health checkpoint rows written. Zero means a crashed session reconstructs counts only. */
    @SerialName("health_checkpoints") val healthCheckpoints: Long = 0,
    @SerialName("clock_offset_ms") val clockOffsetMillis: Double = 0.0,
    @SerialName("clock_delay_ms") val clockDelayMillis: Double = 0.0,
    @SerialName("clock_skew_ppm") val clockSkewPpm: Double = 0.0,
    /**
     * What the pose track amounts to, or null when the session did not track.
     *
     * Null and a zero-sample summary are different facts, and the distinction is the point: null
     * says "this walk never asked for a trajectory", zero says "it asked and got nothing". The
     * second is a broken tracker and the first is a design choice.
     */
    @SerialName("pose_track") val poseTrack: PoseTrackSummary? = null,
    /** What the platform accepted when tracking started, or null when it never did. */
    @SerialName("pose_tracker") val poseTracker: PoseTrackReport? = null,
    /** `waypoint` rows within [markers] — surveyed correspondences for the trajectory fit. */
    @SerialName("waypoints") val waypoints: Long = 0,
    /**
     * The exported geometry, or null when the session produced none.
     *
     * Null covers three different situations and the summary's own fields distinguish them: the session
     * did not track at all, the device has no LiDAR, or scene reconstruction ran and ARKit held no
     * anchors. All three are honest; a zero-triangle summary would not be.
     */
    @SerialName("mesh") val mesh: MeshSummary? = null,
)

@Serializable
data class ClockSampleRecord(
    @SerialName("mono_ns") val monotonicNanos: Long,
    @SerialName("offset_ns") val offsetNanos: Long,
    @SerialName("rtt_ns") val rttNanos: Long,
    @SerialName("skew_ppm") val skewPpm: Double,
    val samples: Int,
)

/**
 * Payload of a [LabPacket.TYPE_SESSION_HELLO] datagram.
 *
 * Kept deliberately small and flat: it is re-sent with every clock burst, and its field names are
 * part of the wire contract with `collectord` (`crates/collector/src/proto.rs`).
 */
@Serializable
data class SessionHello(
    @SerialName("participant_id") val participantId: String,
    val site: String = "",
    @SerialName("ap_id") val apId: String = "",
    val platform: String = "",
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("commanded_rate_hz") val commandedRateHz: Double = 0.0,
)

/** Lifecycle states a session moves through locally before it is safe to delete. */
enum class SessionStatus {
    /** Recording. */
    OPEN,

    /** Recording finished; artefacts on disk, nothing uploaded yet. */
    CLOSED,

    /** Every artefact acknowledged by the server. Only now may local data be deleted. */
    UPLOADED,

    /** Upload attempted and failed. Data is kept, and the console shows it as unsynced. */
    FAILED,
    ;

    val storageKey: String get() = name.lowercase()

    companion object {
        fun fromStorage(value: String?): SessionStatus =
            entries.firstOrNull { it.storageKey == value } ?: OPEN
    }
}

/** Artefact names inside a session's S3 prefix. Fixed so the reader side can rely on them. */
object LabArtefact {
    const val SIDECAR = "metadata.json"
    const val TRAFFIC = "traffic.tsv"
    const val BEACONS = "beacons.tsv"
    const val TRANSITIONS = "transitions.tsv"
    const val CLOCK = "clock.tsv"

    /**
     * Step boundaries and their experimental condition. Without this the other streams are a
     * continuous recording with no machine-readable record of which part was which take.
     */
    const val MARKERS = "markers.tsv"

    /**
     * Ground truth: who was in the room, by explicit human act.
     *
     * Uploaded under the **scanned** lab session id rather than this phone's recording id, because
     * the truth belongs to the session the operator is running, not to one participant's recording
     * of it. Every participant's phone therefore writes its own copy under its own pseudonym
     * prefix, and the collection side unions them into one `ground_truth.csv`.
     */
    const val GROUND_TRUTH = "ground_truth.tsv"

    /**
     * Per-stream liveness, sampled through the session rather than summarised at the end.
     *
     * The sidecar's `health` block is the value at close; this is the trace. A session that ran to
     * completion and looked healthy at `stop()` can have spent forty minutes at 11 % delivery in
     * the middle, and only the trace shows which windows those were — so this is what lets the
     * analysis exclude a degraded interval rather than a whole session.
     */
    const val HEALTH = "health.tsv"

    /**
     * The walk's own trajectory — where the phone was, in a session-local frame.
     *
     * The other half of a fingerprint. Without it the fleet's per-node RSSI on this handset's
     * identity frame is a signal with no place attached, which is a measurement of nothing.
     */
    const val POSE = "pose.tsv"

    /**
     * When each mesh block became what `mesh.ply` contains.
     *
     * The mesh's other half. Geometry with no timestamps is a map that cannot be laid on a CSI capture:
     * this is the file that puts the room on the same `mono_ns` clock as the trajectory and the radio.
     */
    const val MESH_LOG = "mesh.tsv"

    /**
     * The room, as triangles — binary PLY, in the session-local frame `pose.tsv` uses.
     *
     * A blob rather than a stream, and the first artefact in this app that is not a TSV. Carries
     * per-face ARKit semantic labels when the device supplied them, because the ray-traced channel
     * simulator wants materials rather than surfaces.
     */
    const val MESH = "mesh.ply"

    /**
     * What the instrument said while the session ran, on the shared clock.
     *
     * The line that explained walk B's lost mesh existed only on the console and died with it.
     * A session's own words are evidence, and evidence is uploaded.
     */
    const val LOG = "log.tsv"

    /**
     * ARKit's serialised world map — the localization anchor, not a debug dump.
     *
     * A walk that starts from a saved site map relocalises into the *same* frame as every other
     * walk on that site, which turns per-session odometry into positioning. The map is saved at
     * close so the site's map improves as walks accumulate, and uploaded so the frame every
     * session used is reconstructable later.
     */
    const val WORLD_MAP = "worldmap.armap"

    val ALL = listOf(
        SIDECAR, TRAFFIC, BEACONS, TRANSITIONS, CLOCK, MARKERS, HEALTH, POSE, MESH_LOG, MESH,
        LOG, WORLD_MAP, GROUND_TRUTH,
    )
}
