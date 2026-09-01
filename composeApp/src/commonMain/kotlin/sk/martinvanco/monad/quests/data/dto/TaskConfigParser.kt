package sk.martinvanco.monad.quests.data.dto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
     * The guidance image a step may carry, or null.
     *
     * Deliberately NOT part of any per-type config class. An image is chrome — it says
     * "here is where this is" — and every step type can want one, so binding it to
     * [TaskType.WALK_TO] would mean a new config class and an app release the first time a
     * probe wanted a photo of the card it is asking for.
     *
     * Read straight off the raw config for the same reason the sensor config is untyped:
     * the backend stores step config as free-form JSON, so a field the app does not know
     * about costs nothing, and a field the app does know about costs no schema migration.
     *
     * The URL is generated from PostGIS at publish time and is expected to change whenever a
     * card moves. Nothing here caches it beyond Coil's own disk cache, on purpose — a stale
     * map of where a marker used to be is worse than no map.
     */
    fun stepImageUrl(task: ActiveTaskDto): String? {
        val url = (task.config as? JsonObject)
            ?.get("image")
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
        // An empty string is a generator that had nothing to draw, not a request to render
        // a broken image placeholder in the middle of a step.
        return url?.takeIf { it.isNotEmpty() }
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
