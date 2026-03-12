package sk.martinvanco.monad.core.config

expect fun isDebug(): Boolean

object AppConfig {
    /**
     * Base URL for API endpoints
     *
     * Development: http://192.168.100.240
     * Production: https://api.monad.sk
     */
    const val BASE_URL = "https://monad.martinvanco.sk"

    /**
     * Request timeout in milliseconds
     */
    const val REQUEST_TIMEOUT = 5000L
}
