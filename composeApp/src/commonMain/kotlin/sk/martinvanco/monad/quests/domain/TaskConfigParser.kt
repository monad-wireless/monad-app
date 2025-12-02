package sk.martinvanco.monad.quests.domain

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
            TaskType.INFO -> null
        }
    }

    /**
     * Type-safe getters for specific config types
     */
    fun getQrCodeConfig(task: TaskDto): QrCodeConfig? {
        return if (task.type == TaskType.QR_CODE) {
            parseConfig(task.type, task.config) as? QrCodeConfig
        } else null
    }

    fun getQrCodeConfig(task: ActiveTaskDto): QrCodeConfig? {
        return if (task.type == TaskType.QR_CODE) {
            parseConfig(task.type, task.config) as? QrCodeConfig
        } else null
    }

    fun getBleDeviceConfig(task: TaskDto): BleDeviceConfig? {
        return if (task.type == TaskType.FIND_BLE_DEVICE) {
            parseConfig(task.type, task.config) as? BleDeviceConfig
        } else null
    }

    fun getBleDeviceConfig(task: ActiveTaskDto): BleDeviceConfig? {
        return if (task.type == TaskType.FIND_BLE_DEVICE) {
            parseConfig(task.type, task.config) as? BleDeviceConfig
        } else null
    }

    fun getWaitConfig(task: TaskDto): WaitConfig? {
        return if (task.type == TaskType.WAIT) {
            parseConfig(task.type, task.config) as? WaitConfig
        } else null
    }

    fun getWaitConfig(task: ActiveTaskDto): WaitConfig? {
        return if (task.type == TaskType.WAIT) {
            parseConfig(task.type, task.config) as? WaitConfig
        } else null
    }
}
