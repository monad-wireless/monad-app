package sk.martinvanco.monad.core.deeplink

/** The one host these links live on. Top-level so both link shapes read it by one name. */
private const val DEEP_LINK_ROOT = "https://monad.dubec.dev"

/**
 * A link that arrived from outside the app (IP-128, extended by IP-140).
 *
 * Two shapes, and the type is a sealed hierarchy rather than a raw slug string so
 * that adding the next one is a `when` the compiler checks, not a second parser
 * bolted on somewhere else.
 */
sealed interface DeepLink {

    /**
     * The printed payload this link stands for, in canonical form.
     *
     * **Derived, never stored.** A probe matches by folding a scan to its trailing
     * path segment ([sk.martinvanco.monad.quests.data.dto.ProbeConfig.codeKey]), so
     * the canonical URL and whatever the camera actually read fold to the same
     * identity. Keeping the raw text instead would put the incoming URL into this
     * type's equality, which is a property no caller wants and every test would
     * have to restate.
     */
    val scannedValue: String

    /**
     * A QR sticker on a fleet node: `https://monad.dubec.dev/d/monad04`.
     *
     * @param slug the fleet identity, e.g. `monad04`
     * @param questId optional `?q=` — arm one specific quest instead of showing
     *   everything this node offers. Unknown ids degrade to the device view.
     */
    data class Device(val slug: String, val questId: String? = null) : DeepLink {
        override val scannedValue: String get() = "$DEEP_LINK_ROOT/d/$slug"
    }

    /**
     * A printed lab marker card: `https://monad.dubec.dev/m/MONAD-FP-07` (IP-140).
     *
     * Claimed so that pointing a camera at a card costs one action rather than
     * three. Before this the `/m/` prefix belonged to nobody on the handset: the
     * portal answered it with a page saying "a participant with the app should
     * never reach this", which was true and unreachable, because the app did not
     * claim the path — the server's own applink manifest has listed `/m/` paths since
     * IP-129, so the claim was dangling on the handset side alone.
     *
     * The [code] is deliberately not resolved to a place here. A card's payload
     * says nothing about where it is — that is what lets the set be re-laid
     * between arms without a reprint — so the location comes from the quest's own
     * targets, generated out of PostGIS. This type carries identity only.
     */
    data class Marker(val code: String) : DeepLink {
        override val scannedValue: String get() = "$DEEP_LINK_ROOT/m/$code"
    }

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
    private const val MARKER_PATH_PREFIX = "/m/"

    /** Hosts whose links this app is allowed to claim. */
    private val ALLOWED_HOSTS = setOf("monad.dubec.dev")

    private val SLUG_REGEX = Regex("^monad\\d{2}$")

    /**
     * The same shape the portal validates before any lookup (`MARKER_CODE_RE` in
     * `web/marker_index.py`) and the same one the printed registry enforces at load:
     * printable, no whitespace, alphanumeric plus hyphen. Checked here for the same
     * reason it is checked there — a damaged or forged scan should cost a regex,
     * not a navigation into a screen that then has to explain itself.
     */
    private val MARKER_CODE_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")

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

        // Tolerate a trailing slash on both prefixes: Starlette 307s /d/monad01/ to
        // the canonical form, and a QR that picked one up from a label reprint
        // should still work.
        if (path.startsWith(DEVICE_PATH_PREFIX)) {
            val slug = path.removePrefix(DEVICE_PATH_PREFIX).trimEnd('/')
            if (!SLUG_REGEX.matches(slug)) return null
            return DeepLink.Device(slug = slug, questId = query.queryParam("q"))
        }

        if (path.startsWith(MARKER_PATH_PREFIX)) {
            val code = path.removePrefix(MARKER_PATH_PREFIX).trimEnd('/')
            if (!MARKER_CODE_REGEX.matches(code)) return null
            return DeepLink.Marker(code = code)
        }

        return null
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
