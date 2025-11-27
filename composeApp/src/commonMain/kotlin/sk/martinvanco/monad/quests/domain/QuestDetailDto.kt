package sk.martinvanco.monad.quests.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a task within a quest
 */
@Serializable
data class TaskDto(
    val name: String,
    val description: String,
    val type: TaskType,
    val config: JsonElement? = null // Dynamic config based on task type
)

/**
 * Task types that define what action the user needs to perform
 */
@Serializable
enum class TaskType {
    @SerialName("qr_code")
    QR_CODE,

    @SerialName("find_ble_device")
    FIND_BLE_DEVICE,

    @SerialName("wait")
    WAIT,

    @SerialName("text_box")
    TEXT_BOX
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
    @SerialName("expected_value") val expectedValue: String,
    val location: String
) : TaskConfig

/**
 * Configuration for BLE device finding task
 */
@Serializable
data class BleDeviceConfig(
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_id") val deviceId: String = "" // MAC address format: XX:XX:XX:XX:XX:XX (optional - if empty, filter by name only)
) : TaskConfig

/**
 * Configuration for wait/timer task
 */
@Serializable
data class WaitConfig(
    @SerialName("timeout_seconds") val timeoutSeconds: Int
) : TaskConfig

/**
 * No additional configuration needed for text box tasks
 * The description field in TaskDto contains the content
 */

// ============================================================================
// Quest DTOs
// ============================================================================

/**
 * Detailed quest information including all tasks
 */
@Serializable
data class QuestDetailDto(
    val id: String, // UUID
    val name: String,
    val description: String,
    val duration: Int, // in minutes
    val tasks: List<TaskDto>,
    val points: Float,
    val questType: String // e.g., "Scan & Walk", "Network", etc.
)
