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
) {
    companion object {
        const val SCHEMA = "monad-app/session-sidecar/v1"
    }
}

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
)

@Serializable
data class SessionEnvironment(
    val platform: String = "",
    @SerialName("os_version") val osVersion: String = "",
    @SerialName("device_model") val deviceModel: String = "",
    @SerialName("app_version") val appVersion: String = "",
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

    val ALL = listOf(SIDECAR, TRAFFIC, BEACONS, TRANSITIONS, CLOCK)
}
