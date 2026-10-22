package dev.dubec.monad.core.config

actual fun isDebug(): Boolean = dev.dubec.monad.BuildConfig.DEBUG

actual fun apiBaseUrlOverride(): String? =
    dev.dubec.monad.BuildConfig.API_BASE_URL_OVERRIDE.takeIf { it.isNotBlank() }
