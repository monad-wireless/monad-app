package sk.martinvanco.monad.lab.domain

import kotlinx.datetime.TimeZone
import sk.martinvanco.monad.core.domain.marker.MarkerCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure half of check-in: the session id, the card fold, and what a place is called.
 *
 * Pure by house rule — no clock, no I/O, no platform — so these run in `commonTest` on every
 * target. The parts that need a database or a radio are exercised through the boundary tests and
 * on a bench.
 */
class CheckInTest {

    // ── the session id ───────────────────────────────────────────────────────────────────────

    @Test
    fun `session id is the site and the local date`() {
        // 2026-09-18T10:00:00Z
        val instant = 1_789_725_600_000L
        assertEquals(
            "fiit-ground-0-2026-09-18",
            CheckInSessionId.forDay("fiit-ground-0", instant, TimeZone.UTC),
        )
    }

    @Test
    fun `month and day are zero padded so the id sorts`() {
        // 2026-01-05T12:00:00Z. Without padding this would be `…-2026-1-5`, which sorts after
        // `…-2026-10-5` in every string comparison the analysis side will ever do.
        val instant = 1_767_614_400_000L
        assertEquals("s-2026-01-05", CheckInSessionId.forDay("s", instant, TimeZone.UTC))
    }

    @Test
    fun `two phones in one room on one day agree`() {
        // The only property the scheme has to have: one room, one day, one id — otherwise the
        // room tally becomes a dozen tallies of one person each.
        val morning = 1_789_718_400_000L // 08:00 UTC
        val evening = 1_789_761_360_000L // 19:56 UTC
        assertEquals(
            CheckInSessionId.forDay("fiit-ground-0", morning, TimeZone.UTC),
            CheckInSessionId.forDay("fiit-ground-0", evening, TimeZone.UTC),
        )
    }

    @Test
    fun `a blank site yields a blank id, never a bare date`() {
        // A scan filed under `-2026-09-18` would aggregate every deployment in the world into one
        // tally, and nothing downstream could separate the rows again. CheckInService refuses.
        assertEquals("", CheckInSessionId.forDay("", 1_789_725_600_000L, TimeZone.UTC))
    }

    // ── the card fold ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a printed card URL and its bare code are one identity`() {
        assertEquals("monad-fp-07", MarkerCode.key("https://monad.dubec.dev/m/MONAD-FP-07"))
        assertEquals("monad-fp-07", MarkerCode.key("MONAD-FP-07"))
        assertEquals("monad-fp-07", MarkerCode.key("  monad-fp-07/  "))
    }

    @Test
    fun `a node sticker folds too`() {
        assertEquals("monad04", MarkerCode.key("https://monad.dubec.dev/d/monad04"))
    }

    @Test
    fun `a URL on another host is not one of our cards`() {
        // Folding blindly would let anybody print a sticker whose last path segment checks a
        // participant into a surveyed point they are nowhere near.
        assertEquals("", MarkerCode.key("https://example.org/m/MONAD-FP-07"))
    }

    @Test
    fun `query and fragment are stripped before folding`() {
        assertEquals("monad-fp-07", MarkerCode.key("https://monad.dubec.dev/m/MONAD-FP-07?utm=qr#x"))
    }

    // ── what a place is called ───────────────────────────────────────────────────────────────

    @Test
    fun `a named card shows its quest's label`() {
        val place = CheckInPlace(key = "monad-fp-07", label = "Window bay", room = "Reading room")
        assertEquals("Window bay", place.display)
        assertFalse(place.isUnknownCard)
    }

    @Test
    fun `a card with only a room shows the room`() {
        val place = CheckInPlace(key = "monad-fp-07", room = "Reading room")
        assertEquals("Reading room", place.display)
        assertFalse(place.isUnknownCard)
    }

    @Test
    fun `an unnamed card shows its own code, never an invented place`() {
        // The honest answer. A plausible room name that happens to be the wrong one is worse than
        // a code the participant can check against the card in their hand.
        val place = CheckInPlace(key = "monad-fp-07")
        assertEquals("MONAD-FP-07", place.display)
        assertTrue(place.isUnknownCard)
    }

    // ── the ceiling ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a check-in expires at the ceiling and not before`() {
        val started = 1_789_725_600_000L
        val active = CheckInState.Active(
            place = CheckInPlace(key = "monad-fp-07"),
            labSessionId = "fiit-ground-0-2026-09-18",
            startedWallMillis = started,
            startedMonotonicNanos = 0L,
            participantToken = "p1",
        )
        assertFalse(active.isExpired(started + CheckInPolicy.MAX_DURATION_MILLIS - 1))
        assertTrue(active.isExpired(started + CheckInPolicy.MAX_DURATION_MILLIS))
    }

    @Test
    fun `elapsed time never runs backwards`() {
        // A wall clock can step backwards — NTP, a manual change, a timezone edit. The display
        // must not show a negative timer, and the ceiling must not fire early because of it.
        val started = 1_789_725_600_000L
        val active = CheckInState.Active(
            place = CheckInPlace(key = "monad-fp-07"),
            labSessionId = "s-2026-09-18",
            startedWallMillis = started,
            startedMonotonicNanos = 0L,
            participantToken = "p1",
        )
        assertEquals(0L, active.elapsedMillis(started - 60_000L))
    }
}
