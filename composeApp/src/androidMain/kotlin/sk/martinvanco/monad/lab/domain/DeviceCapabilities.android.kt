package sk.martinvanco.monad.lab.domain

import android.content.pm.PackageManager
import android.os.Build
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.core.util.ContextProvider

/**
 * Android capability probe.
 *
 * Depth is the awkward one. Android has no LiDAR guarantee: a handful of devices ship a ToF sensor,
 * ARCore's Depth API works on many more by inference, and neither is reported by a single system
 * flag. Rather than claim [Capability.LIDAR_MESH] on a guess — which would hand a device a
 * room-scan quest it cannot actually satisfy — this reports the weaker
 * [Capability.DEPTH_COARSE] when ARCore is installed and leaves the mesh token to platforms that
 * can answer the question honestly.
 */
actual suspend fun detectCapabilities(): DeviceCapabilities {
    val context = ContextProvider.getContext()
    val pm = context.packageManager
    val tokens = mutableSetOf<String>()

    // minSdk is 29, so WifiNetworkSpecifier + Network.bindSocket are always present.
    tokens += Capability.WIFI_ASSOCIATE
    tokens += Capability.BACKGROUND_RESIDENCY

    if (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
        tokens += Capability.BLE_WITNESS
        // Advertising is claimed with the LE feature rather than probed through the adapter: the
        // adapter's advertiser handle is null whenever Bluetooth is merely switched off, and a
        // toggled radio must not demote the handset out of every broadcast quest. A genuinely
        // advertise-less chipset surfaces at start() as a loud refusal instead.
        tokens += Capability.BLE_ADVERTISE
    }
    if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
        tokens += Capability.CAMERA_QR
    }

    // Capability.POSE_TRACK is NOT claimed, and its absence is the whole point. ARCore could
    // produce the same six-degree-of-freedom stream ARKit does, but `PoseTracker.android.kt` is a
    // deliberate stub: the session must be driven from a GL surface, which is a rendering Activity
    // nobody has written. An Android handset claiming this token would be offered a tracked survey,
    // walk all twenty stops, and upload a session with an empty pose stream — a run that looks
    // complete and is missing the measurement. Add it in the same commit that lands the Activity.
    if (pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_BAROMETER)) {
        tokens += Capability.BAROMETER
    }
    // UWB landed as a platform feature in Android 12.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        pm.hasSystemFeature("android.hardware.uwb")
    ) {
        tokens += Capability.UWB_RANGING
    }
    // Sensor modules are the authority on their own capability: a token is claimed only when the
    // module that would have to satisfy it says it can. Probing the package list separately would
    // let the two disagree, and the disagreement would surface as a quest that was offered and
    // then could not run.
    tokens += availableModuleCapabilities()

    return DeviceCapabilities(
        platform = "android",
        osVersion = Build.VERSION.RELEASE ?: "",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        appVersion = AppConfig.APP_VERSION,
        capabilities = tokens,
    )
}

