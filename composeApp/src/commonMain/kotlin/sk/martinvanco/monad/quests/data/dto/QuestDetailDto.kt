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
        /** The one host a printed payload may name. Anything else is not one of our cards. */
        private const val ALLOWED_HOST = "monad.dubec.dev"

        /**
         * One card's identity, whichever form it was read in. Empty means "not one of ours".
         *
         * Folding to the trailing path segment is what reconciles the two forms that exist in the
         * field: `MONAD-SHOWCASE-IN` is printed as a full URL and named in its quest as a bare
         * code, and before this they were two identities for one piece of card.
         *
         * **A URL is only folded when it names our host.** Folding blindly would make
         * `https://example.org/m/MONAD-FP-07` satisfy a probe, because only the last segment
         * would ever be compared — so anyone could print a sticker that completes a step from
         * anywhere. The card is public and photographable, so this is not a strong secret; it is
         * still the difference between a dwell that happened at a surveyed point and one that did
         * not, and that is the whole value of the measurement.
         *
         * A string with no scheme is treated as a bare code, which is what a hand-typed or
         * legacy-payload card is.
         */
        fun codeKey(raw: String): String {
            var s = raw.trim()
            s = s.substringBefore('?').substringBefore('#')
            s = s.trimEnd('/')
            if (s.isEmpty()) return ""

            if (s.contains("://")) {
                val afterScheme = s.substringAfter("://")
                val host = afterScheme.substringBefore('/').lowercase()
                if (host != ALLOWED_HOST) return ""
                return afterScheme.substringAfterLast('/').lowercase()
            }

            // A bare code must not contain a path either: `a/b` is not a card code.
            return s.lowercase()
        }
    }
}

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
