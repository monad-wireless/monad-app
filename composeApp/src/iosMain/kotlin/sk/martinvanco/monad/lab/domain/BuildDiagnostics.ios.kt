package sk.martinvanco.monad.lab.domain

import platform.Foundation.NSProcessInfo

/**
 * The environment Xcode launched this process with.
 *
 * `NSProcessInfo.environment` is a `Map<Any?, *>` through the Kotlin/Native ObjC bridge, so the
 * keys and values are narrowed here rather than in the pure parser — which stays a plain
 * `Map<String, String>` and therefore testable off-device.
 */
actual fun detectBuildDiagnostics(): BuildDiagnostics {
    val environment = NSProcessInfo.processInfo.environment
        .mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            val text = value as? String ?: return@mapNotNull null
            name to text
        }
        .toMap()
    return BuildDiagnostics.from(environment)
}
