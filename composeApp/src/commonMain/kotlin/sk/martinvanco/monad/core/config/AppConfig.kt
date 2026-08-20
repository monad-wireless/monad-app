package sk.martinvanco.monad.core.config

expect fun isDebug(): Boolean

/**
 * Build-time API host override, or null to use the compiled-in deployment.
 *
 * A phone under test is rarely pointed at the real lab backend: an emulator talks to a
 * host-local server, and a bench rig may run its own. The lab console already lets an
 * operator redirect the *collector* by hand for exactly this reason; this is the same
 * escape hatch for the API. Release builds pass nothing and get the deployment.
 */
expect fun apiBaseUrlOverride(): String?

object AppConfig {
    /**
     * Base URL for API endpoints.
     *
     * Lab deployment: https://api.monad.dubec.dev  (Hetzner CCX33, behind the same nginx that
     * serves monad.dubec.dev; storage is the project's Hetzner Object Storage bucket, so app
     * sessions land next to the `csid` fleet captures under one S3 tenancy.)
     */
    const val DEFAULT_BASE_URL = "https://api.monad.dubec.dev"

    /** Effective API host: the build-time override when one was supplied, else the deployment. */
    val BASE_URL: String = apiBaseUrlOverride()?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    /**
     * Request timeout in milliseconds.
     *
     * Deliberately generous compared with a consumer app: a phone joined to an experiment AP
     * routinely has no route to the internet at all, and the failure we want is a clean timeout
     * that the lab console reports, not a retry storm.
     */
    const val REQUEST_TIMEOUT = 15_000L

    /**
     * Request timeout in milliseconds for large binary artefact uploads (`mesh.ply`,
     * `worldmap.armap`).
     *
     * [REQUEST_TIMEOUT] governs everything else and is deliberately short, to fail fast on a phone
     * with no route out at all. A multi-hundred-megabyte artefact on a real uplink needs the
     * opposite property: Ktor's `HttpTimeout` plugin `requestTimeoutMillis` covers the whole call including the
     * body, so 15 s aborted a 60+ MB `mesh.ply` mid-transfer regardless of what the server allowed.
     * Sized against the backend's own `monad_api_proxy_timeout` ceiling (3000 s,
     * `infra/ansible/roles/monad_api/defaults/main.yml`), not against a UX guess, so the client is
     * never the shorter clock on a slow upload.
     */
    const val UPLOAD_REQUEST_TIMEOUT = 3_000_000L

    /**
     * Marketing version of this build, e.g. `1.2.0`.
     *
     * Not a constant of this file any more, and deliberately so. It used to say `0.3.0-lab` while
     * Gradle said `1.0` and Xcode said `1.1.0`, and the sidecar recorded the one string no build
     * system agreed with. It now comes from [BuildIdentity], which is generated from the single
     * `monad.version` property both platforms build against.
     *
     * For provenance use [BUILD_ID], not this: two builds share a version, they do not share a
     * build id.
     */
    val APP_VERSION: String get() = BuildIdentity.VERSION

    /** Store build number — Android `versionCode`, iOS `CFBundleVersion`. */
    val APP_BUILD: Int get() = BuildIdentity.VERSION_CODE

    /**
     * The identity recorded in every session sidecar as `build_id`.
     *
     * `<version>+<versionCode>.g<commit8>[.dirty<worktree8>]`. The answer to "which build produced
     * this recording?", including the case of two bench builds of the same version from different
     * uncommitted patches.
     */
    val BUILD_ID: String get() = BuildIdentity.BUILD_ID
}
