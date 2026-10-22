package sk.martinvanco.monad.core.config

import platform.Foundation.NSProcessInfo

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
actual fun isDebug(): Boolean = kotlin.native.Platform.isDebugBinary

/**
 * iOS has no Gradle build-time field, so a DEBUG binary reads `MONAD_API_BASE_URL` from the
 * process environment instead: an Xcode scheme variable, or `SIMCTL_CHILD_MONAD_API_BASE_URL` on
 * `xcrun simctl launch`. That is what lets a simulator run against the dev compose stack (local
 * fixtures, App Store screenshots). A release binary ignores it, so an archive always talks to the
 * compiled-in deployment URL.
 */
actual fun apiBaseUrlOverride(): String? {
    if (!isDebug()) return null
    return NSProcessInfo.processInfo.environment["MONAD_API_BASE_URL"] as? String
}
