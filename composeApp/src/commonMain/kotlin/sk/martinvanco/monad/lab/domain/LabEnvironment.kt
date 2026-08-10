package sk.martinvanco.monad.lab.domain

import sk.martinvanco.monad.core.config.AppConfig

/**
 * Platform facts recorded into every sidecar.
 *
 * Kept in its own file so the `expect` sits beside its `actual`s by name — `LabEnvironment.kt`,
 * `LabEnvironment.android.kt`, `LabEnvironment.ios.kt`. It previously lived at the foot of
 * `LabInstrument.kt`, where the pairing was invisible to anyone grepping for the declaration.
 */
expect class LabEnvironment() {
    val platform: String
    val osVersion: String
    val deviceModel: String
    val timezone: String

    /** Interface name the datagram socket should pin to (`en0` on iOS; unused on Android). */
    val wifiInterfaceHint: String?
}

/** App version, kept out of [LabEnvironment] so the expect/actual surface stays platform-only. */
val LabEnvironment.appVersion: String get() = AppConfig.APP_VERSION

/**
 * The build that is doing the recording — `<version>+<code>.g<commit>[.dirty<hash>]`.
 *
 * Same reason as [appVersion] for living here: it is a build fact, not a platform fact, and both
 * platforms compile the same generated constant.
 */
val LabEnvironment.buildId: String get() = AppConfig.BUILD_ID
