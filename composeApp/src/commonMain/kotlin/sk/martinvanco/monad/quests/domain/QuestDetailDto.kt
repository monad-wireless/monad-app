package sk.martinvanco.monad.quests.domain

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

    @SerialName("finish")
    FINISH;

    companion object {
        fun fromStepType(stepType: StepType): TaskType {
            return when (stepType) {
                StepType.START -> START
                StepType.WAIT -> WAIT
                StepType.SCAN_QR -> SCAN_QR
                StepType.CONNECT_TO_AP -> CONNECT_TO_AP
                StepType.WALK_TO -> WALK_TO
                StepType.FIND_BLE_DEVICE -> FIND_BLE_DEVICE
                StepType.FINISH -> FINISH
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
    val questType: String = "Quest"
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
                questType = "Quest"
            )
        }
    }
}
