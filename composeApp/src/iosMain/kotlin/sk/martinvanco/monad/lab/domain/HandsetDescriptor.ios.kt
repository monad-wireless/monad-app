@file:OptIn(ExperimentalForeignApi::class)

package sk.martinvanco.monad.lab.domain

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.ARKit.ARSceneReconstructionMesh
import platform.ARKit.ARWorldTrackingConfiguration
import platform.CoreLocation.CLLocationManager
import platform.CoreMotion.CMAltimeter
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.valueForKey
import platform.NearbyInteraction.NISession
import platform.UIKit.UIDevice
import platform.posix.uname
import platform.posix.utsname
import sk.martinvanco.monad.core.config.AppConfig

/**
 * The hardware identifier: `iPhone15,2`, not `iPhone`.
 *
 * `UIDevice.model` is the family. Two iPhones of different generations carry different IMUs,
 * barometers, UWB chips and LiDAR, and the family string cannot separate them — which is the
 * whole reason a sensor reference needs `utsname.machine`. Shared with [LabEnvironment] so the
 * sidecar's `device_model` and the descriptor's `machine` are the same string from the same call.
 */
internal fun machineIdentifier(): String? = runCatching {
    memScoped {
        val u = alloc<utsname>()
        if (uname(u.ptr) != 0) return@memScoped null
        u.machine.toKString().takeIf { it.isNotBlank() }
    }
}.getOrNull()

/**
 * The OS build (`22G86`), which `systemVersion` (`18.6`) does not carry.
 *
 * Read out of `operatingSystemVersionString` — "Version 18.6 (Build 22G86)" — rather than
 * `sysctlbyname("kern.osversion")`: the Kotlin/Native platform bindings do not export `sysctlbyname`,
 * and the string carries the same value. Absent when the format is not the one Foundation has used
 * since iOS 8, rather than guessed.
 */
private val osBuildPattern = Regex("Build ([A-Za-z0-9]+)")

private fun osBuild(): String? = runCatching {
    osBuildPattern.find(NSProcessInfo.processInfo.operatingSystemVersionString)?.groupValues?.get(1)
}.getOrNull()

/**
 * `thermalState` and `lowPowerModeEnabled` through key-value coding.
 *
 * Both are real `NSProcessInfo` properties since iOS 11 and iOS 9, and neither is in the Kotlin/Native
 * Foundation bindings this build compiles against, so they are read by name at runtime. A missing key
 * throws inside Foundation and lands here as absent, which is the honest outcome.
 */
private fun processInfoInt(key: String): Int? = runCatching {
    (NSProcessInfo.processInfo.valueForKey(key) as? NSNumber)?.intValue
}.getOrNull()

/**
 * iOS descriptor.
 *
 * What iOS can say and what it cannot, honestly: the machine identifier through `utsname`, the OS
 * build out of `operatingSystemVersionString`; per-subsystem availability flags through CoreMotion,
 * CoreLocation, NearbyInteraction and ARKit; thermal and power state through `NSProcessInfo` (KVC). NO radio block — iOS publishes no
 * BLE PHY set and no Wi-Fi standard support API, so `radio` stays `{}` and the admin says so.
 * The battery level needs monitoring switched on for the one read and is restored after it.
 */
actual suspend fun describeHandset(handsetId: String): HandsetDescriptor {
    val capabilities = detectCapabilities()
    val device = UIDevice.currentDevice

    val motion = runCatching { CMMotionManager() }.getOrNull()
    val sensors = buildList {
        motion?.let {
            add(SensorFact("accelerometer", available = it.accelerometerAvailable))
            add(SensorFact("gyroscope", available = it.gyroAvailable))
            add(SensorFact("magnetometer", available = it.magnetometerAvailable))
            add(SensorFact("device_motion", available = it.deviceMotionAvailable))
        }
        runCatching { CMAltimeter.isRelativeAltitudeAvailable() }.getOrNull()
            ?.let { add(SensorFact("barometer", available = it)) }
        runCatching { CLLocationManager.headingAvailable() }.getOrNull()
            ?.let { add(SensorFact("heading", available = it)) }
        runCatching { NISession.isSupported() }.getOrNull()
            ?.let { add(SensorFact("uwb", available = it)) }
        runCatching { ARWorldTrackingConfiguration.supportsSceneReconstruction(ARSceneReconstructionMesh) }.getOrNull()
            ?.let { add(SensorFact("lidar_mesh", available = it)) }
    }

    // NSProcessInfoThermalState: Nominal = 0, Fair = 1, Serious = 2, Critical = 3 (Foundation header order).
    val thermal = when (processInfoInt("thermalState")) {
        0 -> "nominal"
        1 -> "fair"
        2 -> "serious"
        3 -> "critical"
        else -> null
    }
    val batteryPct = runCatching {
        val wasMonitoring = device.batteryMonitoringEnabled
        device.batteryMonitoringEnabled = true
        val level = device.batteryLevel
        device.batteryMonitoringEnabled = wasMonitoring
        // -1.0 is UIKit's "unknown"; absent is the honest encoding of it.
        if (level < 0f) null else (level * 100f).toInt()
    }.getOrNull()

    return HandsetDescriptor(
        handsetId = handsetId,
        platform = "ios",
        machine = machineIdentifier(),
        manufacturer = "Apple",
        model = device.model,
        osVersion = device.systemVersion,
        osBuild = osBuild(),
        appVersion = AppConfig.APP_VERSION,
        buildId = AppConfig.BUILD_ID,
        capabilities = capabilities.capabilities.sorted(),
        sensors = sensors,
        radio = RadioFacts(),
        state = HandsetState(
            thermal = thermal,
            lowPowerMode = processInfoInt("lowPowerModeEnabled")?.let { it != 0 },
            batteryPct = batteryPct,
        ),
    )
}
