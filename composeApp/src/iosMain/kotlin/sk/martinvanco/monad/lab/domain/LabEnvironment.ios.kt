package sk.martinvanco.monad.lab.domain

import kotlinx.datetime.TimeZone
import platform.UIKit.UIDevice

actual class LabEnvironment actual constructor() {
    actual val platform: String = "ios"
    actual val osVersion: String =
        "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}"

    /**
     * `UIDevice.model` is the coarse family ("iPhone"), not the specific hardware. The precise
     * identifier needs a `uname` machine lookup; the family is enough for the sidecar's purpose,
     * which is to explain a session, not to fingerprint the handset.
     */
    actual val deviceModel: String = UIDevice.currentDevice.model
    actual val timezone: String = TimeZone.currentSystemDefault().id

    /** Wi-Fi on every iPhone; the socket pins to it with `IP_BOUND_IF`. */
    actual val wifiInterfaceHint: String? = "en0"
}
