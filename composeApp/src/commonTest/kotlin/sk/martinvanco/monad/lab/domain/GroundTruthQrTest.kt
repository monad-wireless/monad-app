package sk.martinvanco.monad.lab.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The printed code, both directions.
 *
 * The codec is pure precisely so this can be checked without a camera — a code that is misread at
 * the lab is a participant who is not in the data, and there is no second chance at it.
 */
class GroundTruthQrTest {

    @Test
    fun aTicketSurvivesTheRoundTrip() {
        val ticket = GroundTruthTicket(
            labSessionId = "0d2f9d70-4f6b-4a1a-9d6a-2f4b6c8e1a03",
            zoneId = "zone-b",
            site = "fiit-library",
            declaredDirection = GroundTruthDirection.OUT,
        )
        val decoded = assertIs<QrScan.Ok>(GroundTruthQr.parse(GroundTruthQr.encode(ticket)))
        assertEquals(ticket, decoded.ticket)
    }

    @Test
    fun aToggleCodeCarriesNoDeclaredDirection() {
        val ticket = GroundTruthTicket("s1", "zone-a", "site", declaredDirection = null)
        val encoded = GroundTruthQr.encode(ticket)
        assertTrue(encoded.contains("dir=toggle"))
        val decoded = assertIs<QrScan.Ok>(GroundTruthQr.parse(encoded))
        assertEquals(null, decoded.ticket.declaredDirection)
    }

    @Test
    fun charactersThatWouldBreakTheUriSurvive() {
        val ticket = GroundTruthTicket("s&1=x", "zone a/b", "FIIT Library — floor 0")
        val decoded = assertIs<QrScan.Ok>(GroundTruthQr.parse(GroundTruthQr.encode(ticket)))
        assertEquals(ticket.labSessionId, decoded.ticket.labSessionId)
        assertEquals(ticket.zoneId, decoded.ticket.zoneId)
        assertEquals(ticket.site, decoded.ticket.site)
    }

    @Test
    fun theCameraHandsUsPlentyOfThingsThatAreNotPeople() {
        listOf(
            "https://example.org",
            "WIFI:S:lab;T:WPA;P:hunter2;;",
            "",
            "   ",
            "12345678",
        ).forEach {
            assertIs<QrScan.NotOurCode>(GroundTruthQr.parse(it), "should reject '$it'")
        }
    }

    @Test
    fun aCodeFromAnotherProtocolVersionIsRejectedNotMisread() {
        // The /v1 segment is load-bearing: silently reading a v2 code with v1 rules would produce a
        // plausible ticket and a corrupted session.
        val scan = assertIs<QrScan.UnsupportedVersion>(
            GroundTruthQr.parse("monad://ground-truth/v2?session=s&zone=z&dir=in")
        )
        assertEquals("v2", scan.version)
        assertTrue(scan.message.contains("update the app"))
    }

    @Test
    fun aTypoedDirectionIsRejectedRatherThanDefaultedToToggle() {
        // Defaulting would corrupt a whole session's counts in a way nobody notices until analysis.
        val scan = assertIs<QrScan.Malformed>(
            GroundTruthQr.parse("monad://ground-truth/v1?session=s&zone=z&dir=inn")
        )
        assertTrue(scan.reason.contains("inn"))
    }

    @Test
    fun aCodeMissingSessionOrZoneSaysWhichOne() {
        val noSession = assertIs<QrScan.Malformed>(
            GroundTruthQr.parse("monad://ground-truth/v1?zone=z&dir=in")
        )
        assertTrue(noSession.reason.contains("session"))

        val noZone = assertIs<QrScan.Malformed>(
            GroundTruthQr.parse("monad://ground-truth/v1?session=s&dir=in")
        )
        assertTrue(noZone.reason.contains("zone"))
    }

    @Test
    fun surroundingWhitespaceFromAScannerIsTolerated() {
        val encoded = GroundTruthQr.encode(GroundTruthTicket("s1", "zone-c"))
        assertIs<QrScan.Ok>(GroundTruthQr.parse("  $encoded\n"))
    }

    @Test
    fun everyFailureCarriesASentenceAParticipantCanActOn() {
        listOf(
            GroundTruthQr.parse("https://example.org"),
            GroundTruthQr.parse("monad://ground-truth/v9?session=s&zone=z"),
            GroundTruthQr.parse("monad://ground-truth/v1?session=s"),
        ).forEach {
            assertTrue(it.message.isNotBlank(), "every rejection needs a message")
            assertTrue(it.message.length > 20, "and it has to be a sentence, not a code")
        }
    }
}
