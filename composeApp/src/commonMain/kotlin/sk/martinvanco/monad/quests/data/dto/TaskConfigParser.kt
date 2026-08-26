package sk.martinvanco.monad.quests.data.dto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import sk.martinvanco.monad.lab.domain.QuestFeatures

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
            TaskType.START -> json.decodeFromJsonElement(StartConfig.serializer(), configJson)
            TaskType.FINISH -> null
            TaskType.CONNECT_TO_AP -> json.decodeFromJsonElement(ConnectToApConfig.serializer(), configJson)
            TaskType.WALK_TO -> null
            // Sensor config is module-defined and deliberately untyped here: adding a sensor must
            // not require a new config class and an app release.
            TaskType.SENSOR_CAPTURE -> null
            TaskType.BLE_ADVERTISE -> json.decodeFromJsonElement(BleAdvertiseConfig.serializer(), configJson)
            TaskType.PROBE -> json.decodeFromJsonElement(ProbeConfig.serializer(), configJson)
            TaskType.OBSERVE -> json.decodeFromJsonElement(ObserveConfig.serializer(), configJson)
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

    fun getObserveConfig(task: ActiveTaskDto): ObserveConfig? {
        return if (task.type == TaskType.OBSERVE) {
            runCatching { parseConfig(task.type, task.config) as? ObserveConfig }.getOrNull()
        } else null
    }

    fun getProbeConfig(task: ActiveTaskDto): ProbeConfig? {
        return if (task.type == TaskType.PROBE) {
            runCatching { parseConfig(task.type, task.config) as? ProbeConfig }.getOrNull()
        } else null
    }

    fun getConnectToApConfig(task: ActiveTaskDto): ConnectToApConfig? {
        return if (task.type == TaskType.CONNECT_TO_AP) {
            runCatching { parseConfig(task.type, task.config) as? ConnectToApConfig }.getOrNull()
        } else null
    }

    /**
     * What the quest asks of the measurement session, read off the `start` step.
     *
     * Falls back to [QuestFeatures.NONE] on a missing block, a missing start step, or a config this
     * build cannot parse. That default is the pre-IP-140 behaviour, so an unreadable feature block
     * degrades to "the quest asked for nothing" rather than to a session doing something nobody
     * declared.
     */
    fun featuresOf(tasks: List<ActiveTaskDto>): QuestFeatures {
        val start = tasks.firstOrNull { it.type == TaskType.START } ?: return QuestFeatures.NONE
        return runCatching { (parseConfig(TaskType.START, start.config) as? StartConfig)?.features }
            .getOrNull() ?: QuestFeatures.NONE
    }
}
