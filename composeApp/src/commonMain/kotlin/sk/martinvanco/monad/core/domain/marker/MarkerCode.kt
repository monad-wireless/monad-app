package sk.martinvanco.monad.core.domain.marker

/**
 * One printed card's identity, whichever form it was read in.
 *
 * This fold exists in four places already and they must never disagree: `ProbeConfig.codeKey` here,
 * `MarkerService::codeKey` on the backend, `check.py:code_key` in monad-knowledge, and the portal's
 * `marker_key`. A checker that folds differently from the app passes a quest the app cannot match,
 * and the symptom is a card that scans perfectly and silently advances nothing.
 *
 * It moved here because a third caller appeared. Check-in accepts **any** card, so it needs the
 * same identity rule as a probe target, and `lab/domain` may not import `quests/data/dto` (see
 * `LabBoundaryTest`). Copying the function into the lab package would have made two rules where
 * there is one. `ProbeConfig.codeKey` now delegates to this.
 *
 * Pure: no clock, no I/O, no platform. Both ends of a round trip are testable without a camera.
 */
object MarkerCode {

    /** The one host a printed payload may name. Anything else is not one of our cards. */
    const val ALLOWED_HOST: String = "monad.dubec.dev"

    /**
     * Fold a scanned string to its card key. Empty means "not one of ours".
     *
     * Two reductions, both of which the rest of the system already performs somewhere: case is
     * ignored, and a URL collapses to its last path segment. Without the second, a card printed as
     * `https://monad.dubec.dev/m/MONAD-FP-07` and a quest naming the bare code are two identities
     * for one piece of card.
     *
     * **A URL is folded only when it names our host.** Folding blindly would let anyone print a
     * sticker on their own domain whose last segment satisfies a step. The card is public and
     * photographable, so this is not a strong secret; it is still the difference between a dwell
     * that happened at a surveyed point and one that did not, and that is the whole value of the
     * measurement.
     *
     * A string with no scheme is treated as a bare code, which is what a hand-typed or
     * legacy-payload card is. A bare code may not contain a path: `a/b` is not a card.
     */
    fun key(raw: String): String {
        var s = raw.trim()
        s = s.substringBefore('?').substringBefore('#')
        s = s.trimEnd('/')
        if (s.isEmpty()) return ""

        if (s.contains("://")) {
            val afterScheme = s.substringAfter("://")
            val host = afterScheme.substringBefore('/').lowercase()
            if (host != ALLOWED_HOST) return ""
            return afterScheme.substringAfterLast('/').lowercase()
        }

        return s.lowercase()
    }

    /**
     * A human-readable fallback for a card nobody has named.
     *
     * `monad-fp-07` becomes `MONAD-FP-07`. Deliberately not prettified further: inventing
     * "Fingerprint point 7" from a string would be a claim about where the card is, and the app
     * only knows where a card is when a quest's probe target says so. An honest code beats a
     * plausible place name.
     */
    fun display(key: String): String = key.uppercase()
}
