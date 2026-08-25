package sk.martinvanco.monad.quests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import sk.martinvanco.monad.quests.data.dto.ProbeConfig
import sk.martinvanco.monad.quests.data.dto.ProbeTarget

/**
 * The probe's matching rule (IP-140).
 *
 * This is the whole contract between a piece of card taped to a wall and a step in a quest, so it
 * is pinned here rather than left to the composable. Two identical-looking strings that fail to
 * match is a participant standing in a corridor scanning something that does nothing, and it has
 * already happened once on this deployment: `MONAD-SHOWCASE-IN` is printed as a full URL and named
 * in its quest as a bare code, which the old exact compare could never reconcile.
 */
class ProbeConfigTest {

    private val card = ProbeTarget(
        value = "https://monad.dubec.dev/m/MONAD-FP-07",
        label = "Fingerprint point 07",
        room = "library-open",
        kind = "card",
    )

    private val node = ProbeTarget(
        value = "https://monad.dubec.dev/d/monad04",
        label = "Node monad04",
        room = "library-open",
        kind = "node",
    )

    private val config = ProbeConfig(targets = listOf(card, node), dwellSeconds = 30)

    @Test
    fun matchesTheExactPrintedPayload() {
        assertEquals(card, config.match("https://monad.dubec.dev/m/MONAD-FP-07"))
    }

    @Test
    fun matchesRegardlessOfCase() {
        // The app has always compared case-insensitively; a QR reader that up-cases a URL host, or
        // a card reprinted in a different case, must not break the contract.
        assertEquals(card, config.match("HTTPS://MONAD.DUBEC.DEV/M/monad-fp-07"))
    }

    @Test
    fun foldsToTheTrailingPathSegment() {
        // The bridge between the two forms that exist in the field today. The portal's
        // `marker_key()` and the walk console's `waypointCodeFrom` both fold this way; the quest
        // scanner was the one place that did not, which is why two live markers were unmatchable.
        assertEquals(card, config.match("MONAD-FP-07"))
        assertEquals(node, config.match("monad04"))
    }

    @Test
    fun ignoresQueryAndFragmentAndTrailingSlash() {
        // Starlette 307s a trailing slash to the canonical form and a mail client may append a
        // tracking parameter. Neither is a different card.
        assertEquals(card, config.match("https://monad.dubec.dev/m/MONAD-FP-07/"))
        assertEquals(card, config.match("https://monad.dubec.dev/m/MONAD-FP-07?utm=x"))
        assertEquals(card, config.match("https://monad.dubec.dev/m/MONAD-FP-07#top"))
    }

    @Test
    fun ignoresSurroundingWhitespace() {
        assertEquals(card, config.match("  https://monad.dubec.dev/m/MONAD-FP-07  "))
    }

    @Test
    fun rejectsACodeThatIsNotATarget() {
        assertNull(config.match("https://monad.dubec.dev/m/MONAD-FP-19"))
        assertNull(config.match("https://example.org/m/MONAD-FP-07"))
    }

    @Test
    fun rejectsAGroundTruthTicket() {
        // The other QR grammar in this lab. A check-in ticket says "a person crossed into this
        // zone" and feeds the people count; a probe says "the phone was at this point". Matching
        // one as the other would put a position fix into the tally the calibration rests on.
        assertNull(config.match("monad://ground-truth/v1?session=s&zone=A&site=x&dir=in"))
    }

    @Test
    fun rejectsAnEmptyScan() {
        assertNull(config.match(""))
        assertNull(config.match("   "))
    }

    @Test
    fun aSingleTargetProbeAcceptsOnlyThatTarget() {
        // The treasure-hunt leg. "Go to monad04" must not be satisfiable by monad06's sticker,
        // otherwise the ordered walk that makes the trajectory interpretable is not ordered.
        val leg = ProbeConfig(targets = listOf(node), dwellSeconds = 30)
        assertEquals(node, leg.match("monad04"))
        assertNull(leg.match("monad06"))
    }

    @Test
    fun anEmptyTargetListMatchesNothing() {
        // A probe with no targets is an authoring mistake. It must match nothing rather than
        // everything — the opposite default would let any code complete the step.
        assertNull(ProbeConfig().match("MONAD-FP-07"))
    }

    @Test
    fun codeKeyIsStableAcrossEveryFormOfOneCard() {
        val forms = listOf(
            "https://monad.dubec.dev/m/MONAD-FP-07",
            "https://monad.dubec.dev/m/MONAD-FP-07/",
            "MONAD-FP-07",
            "monad-fp-07",
        )
        val keys = forms.map { ProbeConfig.codeKey(it) }.toSet()
        assertTrue(keys.size == 1, "one card produced ${keys.size} identities: $keys")
    }
}
