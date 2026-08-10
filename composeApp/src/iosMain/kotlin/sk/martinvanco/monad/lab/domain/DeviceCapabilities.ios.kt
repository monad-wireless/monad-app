package sk.martinvanco.monad.lab.domain

import platform.UIKit.UIDevice
import sk.martinvanco.monad.core.config.AppConfig

/**
 * iOS capability probe.
 *
 * iOS is the platform that can answer the depth question honestly: ARKit exposes a direct
 * scene-reconstruction support check, which is true exactly on the LiDAR devices (iPhone 12 Pro and
 * later Pro models, iPad Pro 2020+). That check is behind an ARKit dependency the app does not
 * currently link, so the mesh token is withheld rather than guessed — see the room-scan module.
 *
 * Association is reported unconditionally: `NEHotspotConfiguration` is available on every supported
 * iOS version, and the entitlement question surfaces at association time with a clear error rather
 * than as a silent capability gap.
 */
actual suspend fun detectCapabilities(): DeviceCapabilities {
    val device = UIDevice.currentDevice
    val tokens = mutableSetOf(
        Capability.WIFI_ASSOCIATE,
        // A CoreLocation beacon session is both the witness and the background residency, so on
        // iOS these two are the same capability and are reported together.
        Capability.BLE_WITNESS,
        Capability.BACKGROUND_RESIDENCY,
        Capability.CAMERA_QR,
        Capability.BAROMETER,
    )
    // LiDAR and UWB are claimed only when their module's runtime probe agrees.
    tokens += availableModuleCapabilities()

    return DeviceCapabilities(
        platform = "ios",
        osVersion = device.systemVersion,
        deviceModel = device.model,
        appVersion = AppConfig.APP_VERSION,
        capabilities = tokens,
    )
}
