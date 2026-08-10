package sk.martinvanco.monad.core.config

actual fun isDebug(): Boolean = sk.martinvanco.monad.BuildConfig.DEBUG

actual fun apiBaseUrlOverride(): String? =
    sk.martinvanco.monad.BuildConfig.API_BASE_URL_OVERRIDE.takeIf { it.isNotBlank() }
