package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What this handset can actually do, negotiated with the backend.
 *
 * The fleet is deliberately heterogeneous — an operator's iPhone Pro, a participant's mid-range
 * Android, a spare test device — and a quest that needs a capability the phone lacks is not a
 * degraded run, it is a *wrong* run: it produces a session that looks complete and is missing the
 * measurement. So capability is a precondition, not a runtime fallback.
 *
 * The contract is deliberately a flat set of string tokens rather than a typed struct. Adding a
 * sensor then means adding a token on the device and a `required_capabilities` entry on the quest —
 * no schema migration, no app release gating a backend change. That is what makes optional modules
 * (LiDAR, UWB, barometer) additive.
 */
@Serializable
data class DeviceCapabilities(
    val platform: String,
    @SerialName("os_version") val osVersion: String = "",
    @SerialName("device_model") val deviceModel: String = "",
    @SerialName("app_version") val appVersion: String = "",
    /** Capability tokens this device satisfies; see [Capability]. */
    val capabilities: Set<String> = emptySet(),
) {
    fun satisfies(required: Collection<String>): Boolean = capabilities.containsAll(required)

    fun missing(required: Collection<String>): List<String> =
        required.filterNot { it in capabilities }.sorted()
}

/**
 * Known capability tokens.
 *
 * Kept as constants rather than an enum on purpose: the backend may reference a token this app
 * build has never heard of, and the correct behaviour then is "this device does not have it" —
 * which a string set gives for free and an enum would turn into a deserialization crash.
 */
object Capability {
    /** Can associate with a commanded AP and pin a socket to that interface. */
    const val WIFI_ASSOCIATE = "wifi.associate"

    /** Can observe iBeacon anchors, including from the background. */
    const val BLE_WITNESS = "ble.witness"

    /**
     * Can advertise the lab identity frame (a 128-bit service UUID). On iOS this is honest only
     * for the foreground — a backgrounded broadcast moves into Apple's overflow area, which the
     * fleet's raw scanner cannot read — so a quest requiring it must keep the participant in-app.
     */
    const val BLE_ADVERTISE = "ble.advertise"

    /** Can hold background runtime for the length of a session. */
    const val BACKGROUND_RESIDENCY = "background.residency"

    /** Has a camera usable for QR fixes. */
    const val CAMERA_QR = "camera.qr"

    /** Scene-reconstruction-grade depth: ARKit LiDAR mesh, or ARCore depth on capable hardware. */
    const val LIDAR_MESH = "lidar.mesh"

    /** Coarse depth only — good enough for obstacle sensing, not for a floor model. */
    const val DEPTH_COARSE = "depth.coarse"

    /** Ultra-wideband ranging. */
    const val UWB_RANGING = "uwb.ranging"

    /** Barometric altimeter, for floor-level disambiguation. */
    const val BAROMETER = "barometer"
}

/**
 * Detect what this device supports. Implemented per platform because the answer is genuinely
 * different: iOS resolves LiDAR through ARKit's scene-reconstruction support check, Android has no
 * single equivalent and must ask ARCore about depth.
 */
expect suspend fun detectCapabilities(): DeviceCapabilities

/**
 * Capability tokens whose module reports itself runtime-available.
 *
 * Suspending rather than blocking, and that is not a style choice. A probe may itself suspend —
 * acquiring a UWB session scope hops to the main looper — so wrapping it in `runBlocking` from a
 * coroutine deadlocks the caller. That is exactly what happened: the quest list simply never
 * loaded on a handset with a UWB stack, with no error to show for it.
 *
 * A probe that throws counts as "not available": an optional sensor must never be able to stop the
 * app listing quests at all.
 */
suspend fun availableModuleCapabilities(): Set<String> =
    labSensorModules().mapNotNull { module ->
        runCatching { module.probe() }
            .getOrNull()
            ?.let { it as? LabSensorModule.Availability.Available }
            ?.let { module.capability }
    }.toSet()
