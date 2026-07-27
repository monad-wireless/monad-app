package sk.martinvanco.monad.core.config

expect fun isDebug(): Boolean

object AppConfig {
    /**
     * Base URL for API endpoints.
     *
     * Lab deployment: https://api.monad.dubec.dev  (Hetzner CCX33, behind the same nginx that
     * serves monad.dubec.dev; storage is the project's Hetzner Object Storage bucket, so app
     * sessions land next to the `csid` fleet captures under one S3 tenancy.)
     */
    const val BASE_URL = "https://api.monad.dubec.dev"

    /**
     * Request timeout in milliseconds.
     *
     * Deliberately generous compared with a consumer app: a phone joined to an experiment AP
     * routinely has no route to the internet at all, and the failure we want is a clean timeout
     * that the lab console reports, not a retry storm.
     */
    const val REQUEST_TIMEOUT = 15_000L

    /** Recorded into every lab session sidecar; bump with each build that changes measurement. */
    const val APP_VERSION = "0.3.0-lab"
}
