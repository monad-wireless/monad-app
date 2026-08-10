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
