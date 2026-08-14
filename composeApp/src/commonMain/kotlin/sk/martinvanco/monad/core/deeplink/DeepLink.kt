package sk.martinvanco.monad.core.deeplink

/**
 * A link that arrived from outside the app (IP-128).
 *
 * Today there is exactly one shape — a device label — but the type is a sealed
 * hierarchy rather than a raw slug string so that adding the next one (a shared
 * quest, a session invite) is a `when` the compiler checks, not a second parser
 * bolted on somewhere else.
 */
sealed interface DeepLink {

    /**
     * A QR sticker on a fleet node: `https://monad.dubec.dev/d/monad04`.
     *
     * @param slug the fleet identity, e.g. `monad04`
     * @param questId optional `?q=` — arm one specific quest instead of showing
     *   everything this node offers. Unknown ids degrade to the device view.
     */
    data class Device(val slug: String, val questId: String? = null) : DeepLink
}

/**
 * Turns an incoming URL into a [DeepLink], or `null` if it is not ours.
 *
 * Pure and platform-free on purpose: this is the one piece of deep-link logic
 * that can be unit-tested without an Activity, a UIApplication, or a running
 * Koin graph — and it is the piece most likely to be wrong, because it decides
 * what a stranger's camera did.
 *
 * Deliberately strict about case. The URL is matched against a
 * case-sensitive AASA component on iOS, a literal path matcher on Android, and a
 * case-sensitive FastAPI route on the server; accepting `/D/MONAD01` here would
 * make the app disagree with all three about what a valid link is.
 */
object DeepLinkParser {

    private const val DEVICE_PATH_PREFIX = "/d/"

    /** Hosts whose links this app is allowed to claim. */
    private val ALLOWED_HOSTS = setOf("monad.dubec.dev")

    private val SLUG_REGEX = Regex("^monad\\d{2}$")

    /**
     * @param url the raw URL as delivered by the platform
     * @return the parsed link, or `null` when the URL is not a link we handle —
     *   in which case the caller must fall through to normal launch rather than
     *   showing an error, because "not ours" is the common case (a push
     *   notification tap, a share sheet, an OS probe).
     */
    fun parse(url: String?): DeepLink? {
        if (url.isNullOrBlank()) return null

        val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (withoutScheme.isEmpty()) return null

        val host = withoutScheme.substringBefore('/').substringBefore('?').lowercase()
        if (host !in ALLOWED_HOSTS) return null

        val pathAndQuery = withoutScheme.removePrefix(withoutScheme.substringBefore('/'))
        val path = pathAndQuery.substringBefore('?')
        val query = pathAndQuery.substringAfter('?', missingDelimiterValue = "")

        if (!path.startsWith(DEVICE_PATH_PREFIX)) return null

        // Tolerate a trailing slash: Starlette 307s /d/monad01/ to the canonical
        // form, and a QR that picked one up from a label reprint should still work.
        val slug = path.removePrefix(DEVICE_PATH_PREFIX).trimEnd('/')
        if (!SLUG_REGEX.matches(slug)) return null

        return DeepLink.Device(slug = slug, questId = query.queryParam("q"))
    }

    /** Unknown keys are ignored rather than rejected — a mail client appending a
     *  tracking parameter must not break a sticker. */
    private fun String.queryParam(key: String): String? =
        split('&')
            .asSequence()
            .mapNotNull { pair ->
                val name = pair.substringBefore('=')
                if (name != key) null else pair.substringAfter('=', missingDelimiterValue = "").ifBlank { null }
            }
            .firstOrNull()
}
