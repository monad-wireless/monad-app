package sk.martinvanco.monad.quests.data.dto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Utility for parsing task configuration from JSON
 */
object TaskConfigParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse task configuration based on task type
     */
    fun parseConfig(type: TaskType, configJson: JsonElement?): TaskConfig? {
        if (configJson == null) return null

        return when (type) {
            TaskType.QR_CODE, TaskType.SCAN_QR -> json.decodeFromJsonElement(QrCodeConfig.serializer(), configJson)
            TaskType.FIND_BLE_DEVICE -> json.decodeFromJsonElement(BleDeviceConfig.serializer(), configJson)
            TaskType.WAIT -> json.decodeFromJsonElement(WaitConfig.serializer(), configJson)
            TaskType.TEXT_BOX -> null
            TaskType.START -> null
            TaskType.FINISH -> null
            TaskType.CONNECT_TO_AP -> null
            TaskType.WALK_TO -> null
            // Sensor config is module-defined and deliberately untyped here: adding a sensor must
            // not require a new config class and an app release.
            TaskType.SENSOR_CAPTURE -> null
            TaskType.BLE_ADVERTISE -> json.decodeFromJsonElement(BleAdvertiseConfig.serializer(), configJson)
            TaskType.INFO -> null
        }
    }

    /**
     * Type-safe getters for specific config types
     */
    fun getQrCodeConfig(task: ActiveTaskDto): QrCodeConfig? {
        return if (task.type == TaskType.QR_CODE || task.type == TaskType.SCAN_QR) {
            parseConfig(task.type, task.config) as? QrCodeConfig
        } else null
    }

    fun getBleDeviceConfig(task: ActiveTaskDto): BleDeviceConfig? {
        return if (task.type == TaskType.FIND_BLE_DEVICE) {
            parseConfig(task.type, task.config) as? BleDeviceConfig
        } else null
    }

    fun getWaitConfig(task: ActiveTaskDto): WaitConfig? {
        return if (task.type == TaskType.WAIT) {
            parseConfig(task.type, task.config) as? WaitConfig
        } else null
    }

    fun getBleAdvertiseConfig(task: ActiveTaskDto): BleAdvertiseConfig? {
        return if (task.type == TaskType.BLE_ADVERTISE) {
            runCatching { parseConfig(task.type, task.config) as? BleAdvertiseConfig }.getOrNull()
        } else null
    }
}
