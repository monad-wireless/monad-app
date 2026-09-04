@file:OptIn(ExperimentalForeignApi::class)

package sk.martinvanco.monad.lab.domain

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.ARKit.ARSceneReconstructionMesh
import platform.ARKit.ARWorldTrackingConfiguration
import platform.CoreLocation.CLLocationManager
import platform.CoreMotion.CMAltimeter
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalStateCritical
import platform.Foundation.NSProcessInfoThermalStateFair
import platform.Foundation.NSProcessInfoThermalStateNominal
import platform.Foundation.NSProcessInfoThermalStateSerious
import platform.NearbyInteraction.NISession
import platform.UIKit.UIDevice
import platform.posix.size_tVar
import platform.posix.sysctlbyname
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

/** `kern.osversion` — the OS build (`22G86`), which `systemVersion` (`18.6`) does not carry. */
private fun sysctlString(name: String): String? = runCatching {
    memScoped {
        val size = alloc<size_tVar>()
        if (sysctlbyname(name, null, size.ptr, null, 0u) != 0 || size.value == 0uL) return@memScoped null
        val buffer = allocArray<ByteVar>(size.value.toInt())
        if (sysctlbyname(name, buffer, size.ptr, null, 0u) != 0) return@memScoped null
        buffer.toKString().takeIf { it.isNotBlank() }
    }
}.getOrNull()

/**
 * iOS descriptor.
 *
 * What iOS can say and what it cannot, honestly: the machine identifier and OS build through
 * sysctl; per-subsystem availability flags through CoreMotion, CoreLocation, NearbyInteraction
 * and ARKit; thermal and power state through `NSProcessInfo`. NO radio block — iOS publishes no
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

    val processInfo = NSProcessInfo.processInfo
    val thermal = runCatching {
        when (processInfo.thermalState) {
            NSProcessInfoThermalStateNominal -> "nominal"
            NSProcessInfoThermalStateFair -> "fair"
            NSProcessInfoThermalStateSerious -> "serious"
            NSProcessInfoThermalStateCritical -> "critical"
            else -> null
        }
    }.getOrNull()
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
        osBuild = sysctlString("kern.osversion"),
        appVersion = AppConfig.APP_VERSION,
        buildId = AppConfig.BUILD_ID,
        capabilities = capabilities.capabilities.sorted(),
        sensors = sensors,
        radio = RadioFacts(),
        state = HandsetState(
            thermal = thermal,
            lowPowerMode = runCatching { processInfo.lowPowerModeEnabled }.getOrNull(),
            batteryPct = batteryPct,
        ),
    )
}
