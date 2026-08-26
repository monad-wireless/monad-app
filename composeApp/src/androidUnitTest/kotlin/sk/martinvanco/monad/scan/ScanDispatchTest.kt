package sk.martinvanco.monad.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import sk.martinvanco.monad.core.deeplink.DeepLink
import sk.martinvanco.monad.core.deeplink.DeepLinkParser
import sk.martinvanco.monad.scan.presentation.describeUnknown

/**
 * The scan shortcut routes on [DeepLinkParser] and nothing else.
 *
 * That is the property worth pinning. `Marker Placement Record.md` ranked "one scanner,
 * dispatched on payload" first of the app changes worth making — and the way that idea
 * goes wrong is a second grammar growing inside the new screen, so that a card opening
 * the app from the camera roll and the same card scanned in-app disagree about what it
 * is. There is one parser; these tests say so.
 */
class ScanDispatchTest {

    @Test
    fun `a marker card routes to the marker resolver`() {
        val link = DeepLinkParser.parse("https://monad.dubec.dev/m/MONAD-FP-07")
        assertTrue(link is DeepLink.Marker)
        assertEquals("MONAD-FP-07", link.code)
    }

    @Test
    fun `a node sticker routes to the device page`() {
        val link = DeepLinkParser.parse("https://monad.dubec.dev/d/monad04")
        assertTrue(link is DeepLink.Device)
        assertEquals("monad04", link.slug)
    }

    @Test
    fun `a ground-truth ticket is not a quest scan and says which screen wants it`() {
        // The other QR grammar in the same building, and the one a participant will
        // legitimately point this camera at by mistake. It writes to the people tally,
        // which must never be derived from a quest scan — so it is refused here, by
        // name, rather than filed as an unknown code.
        val ticket = "monad://ground-truth/v1?session=s&zone=library-open&site=fiit&dir=in"
        assertNull(DeepLinkParser.parse(ticket))

        val said = describeUnknown(ticket)
        assertTrue(said.contains("check-in", ignoreCase = true), said)
        assertTrue(said.contains("counts people", ignoreCase = true), said)
    }

    @Test
    fun `a foreign code is refused with something a participant can act on`() {
        val said = describeUnknown("https://example.org/m/MONAD-FP-07")
        assertTrue(said.contains("not one of ours"), said)
        // Names what to look for instead. "Unknown code" sends somebody hunting for a
        // fault that is not there.
        assertTrue(said.contains("MONAD"), said)
    }

    @Test
    fun `an arbitrary QR from the world is refused, not routed`() {
        assertNull(DeepLinkParser.parse("https://en.wikipedia.org/wiki/Wi-Fi"))
        assertNull(DeepLinkParser.parse("WIFI:S=eduroam;T=WPA;P=hunter2;;"))
        assertNull(DeepLinkParser.parse(""))
    }
}
