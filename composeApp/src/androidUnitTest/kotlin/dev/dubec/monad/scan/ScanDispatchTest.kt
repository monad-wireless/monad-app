package dev.dubec.monad.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.dubec.monad.core.deeplink.DeepLink
import dev.dubec.monad.core.deeplink.DeepLinkParser

/**
 * A code scanned with the phone's own camera routes on [DeepLinkParser] and nothing else.
 *
 * There is one grammar for a printed code. A card or a node sticker opens its page, and every other
 * QR in the building opens nothing.
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
    fun `a ground-truth ticket is never a quest scan`() {
        // The other QR grammar in the same building. It writes to the people tally, which must
        // never be derived from a quest scan.
        assertNull(DeepLinkParser.parse("monad://ground-truth/v1?session=s&zone=library-open&site=fiit&dir=in"))
    }

    @Test
    fun `an arbitrary QR from the world is refused, not routed`() {
        assertNull(DeepLinkParser.parse("https://example.org/m/MONAD-FP-07"))
        assertNull(DeepLinkParser.parse("https://en.wikipedia.org/wiki/Wi-Fi"))
        assertNull(DeepLinkParser.parse("WIFI:S=eduroam;T=WPA;P=hunter2;;"))
        assertNull(DeepLinkParser.parse(""))
    }
}
