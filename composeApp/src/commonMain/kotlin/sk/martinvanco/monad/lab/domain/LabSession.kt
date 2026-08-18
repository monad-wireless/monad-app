package sk.martinvanco.monad.lab.domain

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
         */
        const val SCHEMA = "monad-app/session-sidecar/v4"
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

    val ALL = listOf(SIDECAR, TRAFFIC, BEACONS, TRANSITIONS, CLOCK, MARKERS, HEALTH, GROUND_TRUTH)
}
