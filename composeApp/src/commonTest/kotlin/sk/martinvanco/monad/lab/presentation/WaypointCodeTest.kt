package sk.martinvanco.monad.lab.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The contract between a piece of card on a wall and a recorded waypoint.
 *
 * The printed marker's QR carries `https://monad.dubec.dev/m/<slug>` so that scanning it does something
 * useful for somebody who has not installed the app. But the **slug** is what the placement record
 * names, and the placement record is the only thing that says which card was where — so the slug is
 * what a waypoint has to carry. Recording the full URL would make every downstream analysis strip the
 * same prefix, and one of them would forget.
 *
 * The numbered pool's template is a mirror of `infra/labels/markers.toml`, which prints the cards. A
 * mismatch there does not fail anything at build time: it produces waypoints naming cards that do not
 * exist, discovered when somebody tries to look up where `MONAD-FP-7` was.
 */
class WaypointCodeTest {

    @Test
    fun theCardSlugIsPulledOutOfTheScannedUrl() {
        assertEquals(
            "MONAD-FP-07",
            LabConsoleState.waypointCodeFrom("https://monad.dubec.dev/m/MONAD-FP-07"),
        )
    }

    @Test
    fun aQueryOrFragmentIsNotPartOfTheSlug() {
        // A QR reprinted with a campaign tag, or a URL a browser has decorated. Neither changes which
        // card it is, and a slug carrying `?src=poster` would match nothing in the placement record.
        assertEquals(
            "MONAD-A-IN",
            LabConsoleState.waypointCodeFrom("https://monad.dubec.dev/m/MONAD-A-IN?src=poster"),
        )
        assertEquals(
            "MONAD-B-OUT",
            LabConsoleState.waypointCodeFrom("https://monad.dubec.dev/m/MONAD-B-OUT#top"),
        )
    }

    @Test
    fun aBareCodeIsPassedThroughUntouched() {
        // A card printed with the bare slug, or an operator typing it off the card. Both are the
        // identity already — rewriting them would be inventing a URL that was never scanned.
        assertEquals("MONAD-FP-12", LabConsoleState.waypointCodeFrom("  MONAD-FP-12 "))
    }

    @Test
    fun somethingThatIsNotOneOfOurCodesIsRecordedAsScanned() {
        // A shop receipt, a Wi-Fi config, a colleague's URL. Recorded verbatim rather than rejected:
        // the operator can see it in the waypoint list and re-record, whereas a silently dropped scan
        // is a waypoint they believe they took.
        val other = "https://example.org/thing"
        assertEquals(other, LabConsoleState.waypointCodeFrom(other))
    }

    @Test
    fun theNumberedPoolMatchesThePrintedTemplate() {
        // Zero-padded to two digits, because that is what `slug = "MONAD-FP-{n:02d}"` prints. An
        // unpadded `MONAD-FP-7` would be a waypoint naming a card that does not exist.
        assertEquals("MONAD-FP-01", LabConsoleState.fingerprintCode(1))
        assertEquals("MONAD-FP-09", LabConsoleState.fingerprintCode(9))
        assertEquals("MONAD-FP-20", LabConsoleState.fingerprintCode(20))
        assertEquals(20, LabConsoleState.FINGERPRINT_CARD_COUNT)
    }

    @Test
    fun aPointOutsideThePoolIsClampedRatherThanFormatted() {
        // The stepper cannot produce these, but a future caller could. Clamping keeps the code inside
        // the set of cards that physically exist; formatting freely would mint `MONAD-FP-00`.
        assertEquals("MONAD-FP-01", LabConsoleState.fingerprintCode(0))
        assertEquals("MONAD-FP-20", LabConsoleState.fingerprintCode(99))
    }

    @Test
    fun aTypedCodeWinsOverTheNumberedPool() {
        // The eight named zone cards are not in the pool, so the field has to be reachable without
        // abandoning the stepper. The button must record what it displays.
        val state = LabConsoleState(waypointPoint = 4, waypointCode = " MONAD-SHOWCASE-IN ")
        assertEquals("MONAD-SHOWCASE-IN", state.pendingWaypointCode)
        assertEquals("MONAD-FP-04", state.copy(waypointCode = "").pendingWaypointCode)
    }
}
