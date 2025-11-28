package sk.martinvanco.monad.core.config

/**
 * Application configuration
 *
 * TODO: Move to environment-specific configuration files for different build variants
 */
object AppConfig {
    /**
     * Base URL for API endpoints
     *
     * Development: http://192.168.100.240
     * Production: https://api.monad.sk
     */
    const val BASE_URL = "http://172.20.10.8"

    /**
     * Request timeout in milliseconds
     */
    const val REQUEST_TIMEOUT = 5000L
}
