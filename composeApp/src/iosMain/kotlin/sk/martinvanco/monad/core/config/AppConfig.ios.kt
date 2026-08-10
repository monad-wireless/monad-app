package sk.martinvanco.monad.core.config

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
actual fun isDebug(): Boolean = kotlin.native.Platform.isDebugBinary

/** iOS has no Gradle build-time field; the deployment URL is compiled in. */
actual fun apiBaseUrlOverride(): String? = null
