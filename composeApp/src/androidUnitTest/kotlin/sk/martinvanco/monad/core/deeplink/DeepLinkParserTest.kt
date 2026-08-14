package sk.martinvanco.monad.core.deeplink

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser decides what a stranger's camera just did (IP-128).
 *
 * It is the only piece of the deep-link path that can be tested without an
 * Activity, a UIApplication or a Koin graph, and it is also the piece where a
 * mistake is worst: too permissive and the app hijacks unrelated URLs on the
 * research site; too strict and a printed sticker — which cannot be recalled —
 * silently stops working.
 */
class DeepLinkParserTest {

    @Test
    fun `parses a device label URL`() {
        assertEquals(
            DeepLink.Device(slug = "monad01"),
            DeepLinkParser.parse("https://monad.dubec.dev/d/monad01"),
        )
    }

    @Test
    fun `parses every slug the fleet prints`() {
        for (n in 1..12) {
            val slug = "monad%02d".format(n)
            assertEquals(
                DeepLink.Device(slug = slug),
                DeepLinkParser.parse("https://monad.dubec.dev/d/$slug"),
                "slug $slug must parse — a printed label cannot be recalled",
            )
        }
    }

    @Test
    fun `tolerates a trailing slash`() {
        // Starlette 307s /d/monad01/ to the canonical form, so both forms exist
        // in the wild and both must open the app.
        assertEquals(
            DeepLink.Device(slug = "monad04"),
            DeepLinkParser.parse("https://monad.dubec.dev/d/monad04/"),
        )
    }

    @Test
    fun `reads an optional quest id`() {
        assertEquals(
            DeepLink.Device(slug = "monad04", questId = "abc-123"),
            DeepLinkParser.parse("https://monad.dubec.dev/d/monad04?q=abc-123"),
        )
    }

    @Test
    fun `ignores unknown query parameters`() {
        // A mail client appending a tracking param must not break a sticker.
        assertEquals(
            DeepLink.Device(slug = "monad04", questId = "x"),
            DeepLinkParser.parse("https://monad.dubec.dev/d/monad04?utm_source=poster&q=x"),
        )
    }

    @Test
    fun `rejects other paths on the same host`() {
        // The research site lives on this domain too. Claiming /search/ or
        // /literature/ would break the website for anyone with the app.
        for (url in listOf(
            "https://monad.dubec.dev/",
            "https://monad.dubec.dev/search/",
            "https://monad.dubec.dev/experiments/",
            "https://monad.dubec.dev/literature/huang2025",
        )) {
            assertNull(DeepLinkParser.parse(url), "$url must not be claimed")
        }
    }

    @Test
    fun `rejects other hosts`() {
        assertNull(DeepLinkParser.parse("https://evil.example/d/monad01"))
        assertNull(DeepLinkParser.parse("https://api.monad.dubec.dev/d/monad01"))
    }

    @Test
    fun `rejects malformed slugs`() {
        for (bad in listOf("monad", "monad1", "monad001", "node01", "../etc", "monad01%20", "")) {
            assertNull(
                DeepLinkParser.parse("https://monad.dubec.dev/d/$bad"),
                "slug '$bad' must not parse",
            )
        }
    }

    @Test
    fun `is case sensitive on the path`() {
        // Three layers match this case-sensitively — Apple's AASA components,
        // Android's literal path matcher, and the FastAPI route. Accepting a
        // different case here would make the app disagree with all of them.
        assertNull(DeepLinkParser.parse("https://monad.dubec.dev/D/monad01"))
        assertNull(DeepLinkParser.parse("https://monad.dubec.dev/d/MONAD01"))
    }

    @Test
    fun `tolerates a differently cased host`() {
        // Hosts are case-insensitive per RFC 3986; only the path is not.
        assertEquals(
            DeepLink.Device(slug = "monad01"),
            DeepLinkParser.parse("https://MONAD.DUBEC.DEV/d/monad01"),
        )
    }

    @Test
    fun `returns null rather than throwing on junk`() {
        // "Not ours" is the common case — push taps, share sheets, OS probes.
        for (junk in listOf(null, "", "   ", "not a url", "https://", "monad01")) {
            assertNull(DeepLinkParser.parse(junk))
        }
    }
}

/**
 * The park/drain holder that works around cold start: Koin is not started until
 * the root composable's `remember` block runs, and the NavigationManager's
 * SharedFlow has `replay = 0`, so a link captured at intent time has nowhere to
 * go until the UI is ready.
 */
class PendingDeepLinkTest {

    @AfterTest
    fun tearDown() = PendingDeepLink.clear()

    @Test
    fun `parks and drains once`() {
        PendingDeepLink.parkUrl("https://monad.dubec.dev/d/monad02")
        assertTrue(PendingDeepLink.isPending())

        assertEquals(DeepLink.Device(slug = "monad02"), PendingDeepLink.consume())

        // Take-once matters: a link that fired again on the next recomposition
        // would yank a participant out of a running quest.
        assertNull(PendingDeepLink.consume())
        assertTrue(!PendingDeepLink.isPending())
    }

    @Test
    fun `ignores a url that is not ours`() {
        PendingDeepLink.parkUrl("https://monad.dubec.dev/search/")
        assertNull(PendingDeepLink.consume())
    }

    @Test
    fun `a newer scan replaces an unconsumed one`() {
        PendingDeepLink.parkUrl("https://monad.dubec.dev/d/monad01")
        PendingDeepLink.parkUrl("https://monad.dubec.dev/d/monad05")
        assertEquals(DeepLink.Device(slug = "monad05"), PendingDeepLink.consume())
    }
}
