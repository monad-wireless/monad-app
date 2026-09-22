package sk.martinvanco.monad.quests.data.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import sk.martinvanco.monad.core.domain.marker.MarkerCode
import sk.martinvanco.monad.home.data.dto.QuestDetailResponseDto
import sk.martinvanco.monad.lab.domain.QuestFeatures
import sk.martinvanco.monad.home.data.dto.StepResponseDto
import sk.martinvanco.monad.home.data.dto.StepType

/**
 * Represents a task within a quest
 */
@Serializable
data class TaskDto(
    val id: String = "",
    val name: String,
    val description: String,
    val type: TaskType,
    val order: Int = 0,
    val config: JsonElement? = null
) {
    companion object {
        fun fromStepResponse(step: StepResponseDto): TaskDto {
            return TaskDto(
                id = step.id,
                name = step.name,
                description = "",
                type = TaskType.fromStepType(step.type),
                order = step.order,
                config = step.config
            )
        }
    }
}

/**
 * Task types that define what action the user needs to perform
 */
@Serializable
/**
 * Serializer that degrades an unknown step type to [TaskType.INFO] instead of throwing.
 *
 * This is what makes the step catalogue extensible. Without it, adding a step type on the server —
 * a new sensor module, a seasonal mechanic — makes *every deployed app build* fail to parse the
 * whole quest, which surfaces to the participant as "Network error. Please check your connection."
 * and is impossible to diagnose from the phone. Verified: adding `sensor_capture` broke quest
 * detail on the previous build exactly that way.
 *
 * Degrading to INFO means an older app shows the step as a plain instruction card and can still
 * walk the quest, while a current build runs it properly. The backend can therefore ship a new
 * capability without a coordinated app release.
 */
object TaskTypeSerializer : KSerializer<TaskType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TaskType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TaskType) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): TaskType {
        val raw = decoder.decodeString()
        return TaskType.entries.firstOrNull { it.wire == raw } ?: TaskType.INFO
    }
}

@Serializable(with = TaskTypeSerializer::class)
enum class TaskType {
    @SerialName("start")
    START,

    @SerialName("qr_code")
    QR_CODE,

    @SerialName("scan_qr")
    SCAN_QR,

    @SerialName("find_ble_device")
    FIND_BLE_DEVICE,

    @SerialName("wait")
    WAIT,

    @SerialName("text_box")
    TEXT_BOX,

    @SerialName("connect_to_ap")
    CONNECT_TO_AP,

    @SerialName("walk_to")
    WALK_TO,

    /** Run an optional sensor module — room scan, UWB ranging. Gated by device capability. */
    @SerialName("sensor_capture")
    SENSOR_CAPTURE,

    /** Broadcast the lab identity frame for the step's duration. Gated by `ble.advertise`. */
    @SerialName("ble_advertise")
    BLE_ADVERTISE,

    /**
     * IP-140 — scan one of a named set of surveyed points, then hold still for a fixed dwell.
     *
     * A probe never touches the radio. The identity frame is session-scoped and declared once in
     * the start step's `features` block, so it stays on air across the walk *between* two probes —
     * which is the part of the record the fleet's per-node RSSI reconstructs a trajectory from.
     */
    @SerialName("probe")
    PROBE,

    /**
     * IP-140 — the human headcount.
     *
     * The one channel in this lab that counts *people* rather than phones. Every
     * other stream observes a handset, and every other stream therefore shares the
     * same blind spot: anybody without the app. A person looking up and counting
     * does not, which is why the two must never be derived from each other — the
     * gap between them is the quantity a later experiment measures.
     */
    @SerialName("observe")
    OBSERVE,

    @SerialName("finish")
    FINISH,

    @SerialName("info")
    INFO;

    /** Wire value, matching the backend's `QuestStepType` enum. */
    val wire: String
        get() = when (this) {
            START -> "start"
            QR_CODE -> "qr_code"
            SCAN_QR -> "scan_qr"
            FIND_BLE_DEVICE -> "find_ble_device"
            WAIT -> "wait"
            TEXT_BOX -> "text_box"
            CONNECT_TO_AP -> "connect_to_ap"
            WALK_TO -> "walk_to"
            SENSOR_CAPTURE -> "sensor_capture"
            BLE_ADVERTISE -> "ble_advertise"
            PROBE -> "probe"
            OBSERVE -> "observe"
            FINISH -> "finish"
            INFO -> "info"
        }

    companion object {
        fun fromStepType(stepType: StepType): TaskType {
            return when (stepType) {
                StepType.START -> START
                StepType.WAIT -> WAIT
                StepType.SCAN_QR -> SCAN_QR
                StepType.CONNECT_TO_AP -> CONNECT_TO_AP
                StepType.WALK_TO -> WALK_TO
                StepType.FIND_BLE_DEVICE -> FIND_BLE_DEVICE
                StepType.SENSOR_CAPTURE -> SENSOR_CAPTURE
                StepType.BLE_ADVERTISE -> BLE_ADVERTISE
                StepType.PROBE -> PROBE
                StepType.OBSERVE -> OBSERVE
                StepType.FINISH -> FINISH
                // A step type this build predates: shown as a plain instruction card so the
                // participant can still walk the quest.
                StepType.UNKNOWN -> INFO
            }
        }
    }
}

// ============================================================================
// Task Configuration DTOs
// ============================================================================

/**
 * Base interface for all task configurations
 */
sealed interface TaskConfig

/**
 * Configuration for QR Code scanning task
 */
@Serializable
data class QrCodeConfig(
    @SerialName("expected_value") val expectedValue: String? = null,
    @SerialName("qr_code_id") val qrCodeId: String? = null,
    val location: String? = null
) : TaskConfig

/**
 * Configuration for BLE device finding task
 */
@Serializable
data class BleDeviceConfig(
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_id") val deviceId: String = ""
) : TaskConfig

/**
 * Configuration for wait/timer task
 */
@Serializable
data class WaitConfig(
    @SerialName("timeout_seconds") val timeoutSeconds: Int
) : TaskConfig

/**
 * Configuration for the identity-broadcast task. The identity itself is deliberately absent: it is
 * derived on the phone from the lab bundle's namespace and the running session, never authored
 * into a quest a participant can read.
 */
@Serializable
data class BleAdvertiseConfig(
    @SerialName("duration_seconds") val durationSeconds: Int,
    /** Commanded interval; Android rounds it onto a bucket, iOS ignores it. Null = bundle default. */
    @SerialName("adv_interval_ms") val advIntervalMs: Int? = null,
    /** `ultra_low` | `low` | `medium` | `high`; Android only. Null = bundle default. */
    @SerialName("tx_power") val txPower: String? = null,
) : TaskConfig

/**
 * One surveyed point a [ProbeConfig] will accept, as generated from the PostGIS placement layouts.
 *
 * The card itself is deliberately anonymous — `MONAD-FP-07` does not say where it is, so the set can
 * be re-laid between arms without a reprint. The label and the room therefore have to arrive from
 * somewhere the operator controls per arm, and that is the quest: `monad-knowledge lab quest-build`
 * reads the placements and writes them here. Nothing on the handset holds a marker table.
 */
@Serializable
data class ProbeTarget(
    /** The exact string the QR carries. Matched case-insensitively, trailing path segment folded. */
    val value: String,
    /** What the participant is told they found. */
    val label: String = "",
    /** The surveyed room, e.g. `library-open`. */
    val room: String = "",
    /**
     * `card` or `node`.
     *
     * Not decoration. A dwell at a node sticker sits at zero distance from one end of every link
     * that node terminates, which is the degenerate corner of the geometry; a dwell at a marker
     * card samples open floor. An analysis that pools the two produces a statistic nobody can read,
     * so the kind travels with the waypoint.
     */
    val kind: String = "",
)

/**
 * Configuration for the IP-140 probe step: scan one of these, then hold still.
 *
 * One target makes a treasure-hunt leg — the step names the node to find. Many targets make a
 * fingerprint probe that accepts whichever code the participant happens to be standing at, which is
 * what keeps that quest to a single step and a single scan.
 */
@Serializable
data class ProbeConfig(
    val targets: List<ProbeTarget> = emptyList(),
    @SerialName("dwell_seconds") val dwellSeconds: Int = 30,
) : TaskConfig {

    /**
     * The target a scan satisfies, or null.
     *
     * Two foldings, both of which the rest of the system already performs somewhere: case is
     * ignored (`QrCodeStep` has always done this) and a URL collapses to its last path segment
     * (`marker_key()` on the portal, `waypointCodeFrom` in the walk console). Without the second,
     * a card printed as `https://monad.dubec.dev/m/MONAD-FP-07` and a quest carrying the bare code
     * are two identities for one piece of card — which is live today for the two showcase markers.
     */
    fun match(scanned: String): ProbeTarget? {
        val key = codeKey(scanned)
        if (key.isEmpty()) return null
        return targets.firstOrNull { codeKey(it.value) == key }
    }

    companion object {
        /**
         * One card's identity, whichever form it was read in. Empty means "not one of ours".
         *
         * Delegates to [MarkerCode.key], which is now the single statement of the rule. It moved
         * out of this file when check-in — which accepts any card — needed the same fold from
         * `lab/domain`, a package that may not import a DTO. Kept as a named function here
         * because `ProbeConfig.codeKey` is what the callers and the comments across three
         * repositories refer to.
         */
        fun codeKey(raw: String): String = MarkerCode.key(raw)
    }
}

/**
 * Configuration for the IP-140 headcount step.
 *
 * `minReadings` has no default here for the same reason the backend requires it: a
 * count step that silently accepts one reading and completes is the difference
 * between a measurement and an anecdote. The value comes from the quest or the
 * step does not run.
 */
@Serializable
data class ObserveConfig(
    /** The question, asked the same way of every participant so the answers pool. */
    val prompt: String = "",
    /** How many separate readings the participant must record before the step completes. */
    @SerialName("min_readings") val minReadings: Int = 0,
    /**
     * Ceiling on the counter, or null when nobody has stated the room's capacity.
     *
     * Null is a real answer. A fabricated ceiling would be a claim about the room
     * that no survey supports, and the widget would enforce it silently.
     */
    @SerialName("max_count") val maxCount: Int? = null,
    /**
     * IP-162: `monad-quest/observe/v2` names the room-sweep contract. Absent on every snapshot
     * frozen before it, which is the legacy partial-view contract above — never a sweep.
     */
    val schema: String? = null,
    /** `room_sweep_cumulative`, the one mode v2 defines. Anything else is refused on this client. */
    val mode: String? = null,
    @SerialName("protocol_id") val protocolId: String? = null,
    /** Digest of the frozen protocol; every v3 event repeats it so a run names the rules it ran under. */
    @SerialName("protocol_sha256") val protocolSha256: String? = null,
    /** The rooms a participant may select at sweep start. One room is preselected and confirmed. */
    val rooms: List<SweepRoom> = emptyList(),
    val checkpoints: SweepCheckpointPolicy? = null,
    @SerialName("observer_convention") val observerConvention: String? = null,
) : TaskConfig {

    /** A v2 room sweep this build knows how to run. */
    val isSweep: Boolean
        get() = schema == SWEEP_SCHEMA && mode == SWEEP_MODE

    /** No schema at all: the pre-IP-162 partial-view contract. */
    val isLegacy: Boolean get() = schema == null

    /**
     * A schema or mode this build does not know. Neither legacy nor sweep: the step refuses to
     * run rather than guess which of two measurements the author meant.
     */
    val isUnsupported: Boolean get() = !isLegacy && !isSweep

    /** What a sweep step is missing before it can start, empty when nothing. */
    fun sweepProblems(): List<String> {
        if (!isSweep) return emptyList()
        val out = mutableListOf<String>()
        if (protocolId.isNullOrBlank()) out += "protocol_id"
        if (protocolSha256.isNullOrBlank()) out += "protocol_sha256"
        if (rooms.isEmpty()) out += "rooms"
        if (checkpoints == null) out += "checkpoints"
        if (observerConvention != OBSERVER_EXCLUDE_SELF) out += "observer_convention"
        if (prompt.isBlank()) out += "prompt"
        return out
    }

    companion object {
        const val SWEEP_SCHEMA = "monad-quest/observe/v2"
        const val SWEEP_MODE = "room_sweep_cumulative"
        const val OBSERVER_EXCLUDE_SELF = "exclude_self"
    }
}

/** One room a sweep may cover, as the quest builder froze it from GIS. The phone never authors one. */
@Serializable
data class SweepRoom(
    @SerialName("room_id") val roomId: String,
    @SerialName("floor_id") val floorId: String,
    val label: String,
    @SerialName("geometry_version") val geometryVersion: String,
    @SerialName("coverage_version") val coverageVersion: String,
    @SerialName("coverage_instructions") val coverageInstructions: String,
)

@Serializable
data class SweepCheckpointPolicy(
    @SerialName("min_checkpoints") val minCheckpoints: Int = 0,
    @SerialName("required_for_complete") val requiredForComplete: Boolean = false,
)

/**
 * Configuration for `connect_to_ap`.
 *
 * No SSID and no password: step config is served to every authenticated caller, so a credential
 * here is a published credential. `ap_id` selects one of the lab bundle's access points and the
 * handset reads the key from there — the same rule `ble_advertise` follows for the advertise
 * namespace.
 */
@Serializable
data class ConnectToApConfig(
    @SerialName("ap_id") val apId: String = "",
    /** Seconds to wait for association plus a verified route before giving up. */
    @SerialName("verify_timeout_seconds") val verifyTimeoutSeconds: Int = 30,
) : TaskConfig

/**
 * Configuration for the `start` step. Only the feature block is typed; the prose is free.
 *
 * [QuestFeatures] itself lives in `lab.domain`, beside [sk.martinvanco.monad.lab.domain.SessionRequest]
 * whose roles it names — see the KDoc there for why.
 */
@Serializable
data class StartConfig(
    val features: QuestFeatures = QuestFeatures.NONE,
) : TaskConfig

// ============================================================================
// Quest DTOs
// ============================================================================

/**
 * Detailed quest information including all tasks
 */
@Serializable
data class QuestDetailDto(
    val id: String,
    val name: String,
    val description: String,
    val duration: Int?,
    val tasks: List<TaskDto>,
    val points: Float,
    val questType: String = "Quest",
    val imageUrl: String? = null
) {
    companion object {
        fun fromResponse(response: QuestDetailResponseDto): QuestDetailDto {
            return QuestDetailDto(
                id = response.id,
                name = response.name,
                description = response.description,
                duration = response.estimatedDuration,
                tasks = response.steps
                    .sortedBy { it.order }
                    .map { TaskDto.fromStepResponse(it) },
                points = response.points,
                questType = "Quest",
                imageUrl = response.featuredImage
            )
        }
    }
}
