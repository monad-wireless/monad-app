package sk.martinvanco.monad.lab.domain

import android.os.Build
import kotlinx.datetime.TimeZone

actual class LabEnvironment actual constructor() {
    actual val platform: String = "android"
    actual val osVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    actual val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    actual val timezone: String = TimeZone.currentSystemDefault().id

    /**
     * Unused on Android: pinning goes through `Network.bindSocket()`, which addresses the network
     * the connectivity manager granted rather than a named interface.
     */
    actual val wifiInterfaceHint: String? = null
}
