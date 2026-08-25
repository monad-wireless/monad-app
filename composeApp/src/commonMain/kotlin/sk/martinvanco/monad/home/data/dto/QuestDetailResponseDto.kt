package sk.martinvanco.monad.home.data.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class QuestDetailResponseDto(
    val id: String,
    val name: String,
    val description: String,
    val points: Float,
    val estimatedDuration: Int?,
    val createdAt: String,
    val steps: List<StepResponseDto>,
    val featuredImage: String? = null
)

@Serializable
data class StepResponseDto(
    val id: String,
    val name: String,
    val type: StepType,
    val order: Int,
    val config: JsonElement? = null
)

@Serializable
/**
 * Tolerant step-type serializer for the quest-detail DTO.
 *
 * This is the *second* step-type enum in the app — `quests/data/dto/TaskType` is the other — and
 * making only one of them forward-compatible fixed nothing: adding `sensor_capture` server-side
 * still broke the whole quest with an unexplained "Network error", just from the other layer.
 * Duplicated enums over the same wire contract are exactly how that recurs, so both now degrade
 * unknown values to [StepType.UNKNOWN] instead of throwing.
 */
object StepTypeSerializer : KSerializer<StepType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StepType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: StepType) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): StepType {
        val raw = decoder.decodeString()
        return StepType.entries.firstOrNull { it.wire == raw } ?: StepType.UNKNOWN
    }
}

@Serializable(with = StepTypeSerializer::class)
enum class StepType {
    @SerialName("start")
    START,

    @SerialName("wait")
    WAIT,

    @SerialName("scan_qr")
    SCAN_QR,

    @SerialName("connect_to_ap")
    CONNECT_TO_AP,

    @SerialName("walk_to")
    WALK_TO,

    @SerialName("find_ble_device")
    FIND_BLE_DEVICE,

    @SerialName("sensor_capture")
    SENSOR_CAPTURE,

    /** Broadcast the lab identity frame for the step's duration; gated by `ble.advertise`. */
    @SerialName("ble_advertise")
    BLE_ADVERTISE,

    /** IP-140 — scan one of a named set of surveyed points, then hold still for a fixed dwell. */
    @SerialName("probe")
    PROBE,

    @SerialName("finish")
    FINISH,

    /** A step type this build does not know about; rendered as a plain instruction. */
    @SerialName("unknown")
    UNKNOWN;

    val wire: String
        get() = when (this) {
            START -> "start"
            WAIT -> "wait"
            SCAN_QR -> "scan_qr"
            CONNECT_TO_AP -> "connect_to_ap"
            WALK_TO -> "walk_to"
            FIND_BLE_DEVICE -> "find_ble_device"
            SENSOR_CAPTURE -> "sensor_capture"
            BLE_ADVERTISE -> "ble_advertise"
            PROBE -> "probe"
            FINISH -> "finish"
            UNKNOWN -> "unknown"
        }
}
