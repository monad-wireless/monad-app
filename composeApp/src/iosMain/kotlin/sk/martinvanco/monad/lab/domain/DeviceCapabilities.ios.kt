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
        // Every supported iPhone can play the peripheral role. The honest caveat — the frame is
        // readable by the fleet only while the app is foregrounded — is behavioural, enforced and
        // recorded by IdentityBroadcaster rather than hidden behind a withheld token.
        Capability.BLE_ADVERTISE,
        Capability.CAMERA_QR,
        Capability.BAROMETER,
        // BLE_WITNESS and BACKGROUND_RESIDENCY are NOT claimed here any more (2026-08-26).
        //
        // On iOS they were the same capability — one CoreLocation beacon session served as both —
        // and this build has no location capability at all. Claiming them would not be a cosmetic
        // overstatement: the capability set is sent to `GET /api/quests`, and the backend uses it
        // to withhold quests a handset cannot run. An iPhone claiming a witness token would be
        // offered a witness quest, start it, and record nothing, which is precisely the silent
        // failure the capability filter exists to prevent.
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
