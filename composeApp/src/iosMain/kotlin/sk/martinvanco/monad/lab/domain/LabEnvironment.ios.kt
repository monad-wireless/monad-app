package sk.martinvanco.monad.lab.domain

import kotlinx.datetime.TimeZone
import platform.UIKit.UIDevice

actual class LabEnvironment actual constructor() {
    actual val platform: String = "ios"
    actual val osVersion: String =
        "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}"

    /**
     * The hardware identifier (`iPhone15,2`), from `utsname.machine` — IP-149.
     *
     * This used to be `UIDevice.model`, the family ("iPhone"), on the argument that the sidecar
     * explains a session rather than fingerprinting the handset. The argument did not survive the
     * sensor reference: two iPhones of different generations carry different IMUs, barometers, UWB
     * chips and LiDAR, and a walk's odometry quality is a function of which one recorded it. The
     * family is kept as the fallback for the one case the lookup fails.
     */
    actual val deviceModel: String = machineIdentifier() ?: UIDevice.currentDevice.model
    actual val timezone: String = TimeZone.currentSystemDefault().id

    /** Wi-Fi on every iPhone; the socket pins to it with `IP_BOUND_IF`. */
    actual val wifiInterfaceHint: String? = "en0"
}
