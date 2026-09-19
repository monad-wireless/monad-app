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
     * The public site, which serves the rendered plans (`/lab/step-map.svg`,
     * `/lab/coverage.svg`).
     *
     * Derived from [BASE_URL] rather than declared separately, so a build pointed at a
     * staging API does not silently fetch its pictures from production. The two are the same
     * host with and without the `api.` prefix, behind one nginx.
     */
    val SITE_URL: String = BASE_URL.replace("://api.", "://")

    /**
     * Where a participant writes when the app cannot help them itself.
     *
     * The one address this project shows a participant. It is the same one on the site's privacy
     * page, on the `/join` signup form and in the consent text the signup stores, and keeping it
     * the same is the point: somebody who signed up on a web page and is now stuck on this login
     * form should recognise the address rather than wonder whether they have reached the right
     * project.
     *
     * A constant rather than a string resource, because it is not translated and must not drift
     * between the two locale files.
     */
    const val SUPPORT_EMAIL = "jakub.dubec@stuba.sk"

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
     * Request timeout for ONE part of a multipart artefact upload.
     *
     * Bounded work deserves a bounded clock. [UPLOAD_REQUEST_TIMEOUT] covers a whole artefact, so a
     * 103 MB `mesh.ply` on a stalled connection could sit for fifty minutes before the client
     * noticed anything was wrong — and the 2026-08-26 survey walk lost that artefact to a dropped
     * socket that nobody saw. A part is at most a few megabytes, so five minutes is generous even on
     * a bad uplink, and a part that exceeds it is a stall rather than a slow transfer: the retry
     * re-sends that one part instead of the whole file.
     */
    const val UPLOAD_PART_TIMEOUT = 300_000L

    /**
     * Socket (inter-byte) timeout for ordinary API calls.
     *
     * SET EXPLICITLY, because not setting it is not the same as not having one. Ktor's `HttpTimeout`
     * plugin leaves an unconfigured `socketTimeoutMillis` to the engine, and OkHttp's default read
     * timeout is TEN SECONDS — so every call in this app has always carried a 10 s inter-byte clock
     * that no constant in this file mentioned, whatever [REQUEST_TIMEOUT] said.
     */
    const val SOCKET_TIMEOUT = 15_000L

    /**
     * Socket (inter-byte) timeout for artefact uploads.
     *
     * MEASURED, 2026-09-07. A Samsung SM-S928B lost two `clock.tsv` uploads — a 158-byte file — to
     * `Socket timeout has expired` at 9.995 s and 10.012 s, and nginx logged both as 499 with
     * `upstream_status "-"`. The server had not failed: Tempo trace `51c05182a35a5a03b4f6892abf187306`
     * shows `POST api_storage_session_upload` completing in 9372 ms with a 200, of which 9347 ms was
     * one synchronous `PUT` to `fsn1.your-objectstorage.com`. The handset stopped listening 600 ms
     * before its own upload succeeded, then re-sent it.
     *
     * A socket timeout measures SILENCE, not transfer, and the backend is silent for exactly as long
     * as Hetzner takes to accept the object. So this is sized against that hop rather than against
     * the body: [UPLOAD_REQUEST_TIMEOUT] already bounds the whole call, and [UPLOAD_PART_TIMEOUT]
     * bounds one part. Two minutes still fails a phone with no route out inside one dwell, and it no
     * longer fails a phone whose upload is merely waiting on object storage.
     */
    const val UPLOAD_SOCKET_TIMEOUT = 120_000L

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
