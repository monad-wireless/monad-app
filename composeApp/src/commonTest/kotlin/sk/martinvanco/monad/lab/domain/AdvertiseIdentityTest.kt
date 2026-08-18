package sk.martinvanco.monad.lab.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The advertised UUID is a wire contract shared with the fleet: csid matches on the first twelve
 * bytes and the analysis joins on the last four, so both halves must be exact and stable. A drift
 * here corrupts joins silently — the frame still scans, it just belongs to nobody.
 */
class AdvertiseIdentityTest {

    private val namespace = "6d6f6e61-6461-4076-b100-000000000000"

    @Test
    fun namespacePrefixSurvivesVerbatim() {
        val uuid = AdvertiseIdentity.serviceUuid(namespace, "participant-7", "session-a")
        assertNotNull(uuid)
        // Bytes 0–11 = the first 24 hex digits; dashes fall where they fall.
        assertTrue(uuid.startsWith("6d6f6e61-6461-4076-b100-"))
        assertEquals(36, uuid.length)
    }

    @Test
    fun identityLandsInTheLastFourBytes() {
        val uuid = AdvertiseIdentity.serviceUuid(namespace, "participant-7", "session-a")!!
        val tail = uuid.takeLast(8)
        val participantKey = AdvertiseIdentity.fold16("participant-7")
        val sessionKey = AdvertiseIdentity.fold16("session-a")
        assertEquals(
            participantKey.toString(16).padStart(4, '0') + sessionKey.toString(16).padStart(4, '0'),
            tail,
        )
    }

    @Test
    fun derivationIsDeterministic() {
        val a = AdvertiseIdentity.serviceUuid(namespace, "p", "s")
        val b = AdvertiseIdentity.serviceUuid(namespace, "p", "s")
        assertEquals(a, b)
    }

    @Test
    fun differentSessionsGetDifferentFrames() {
        val a = AdvertiseIdentity.serviceUuid(namespace, "p", "session-1")
        val b = AdvertiseIdentity.serviceUuid(namespace, "p", "session-2")
        assertNotEquals(a, b)
    }

    @Test
    fun differentParticipantsGetDifferentFrames() {
        val a = AdvertiseIdentity.serviceUuid(namespace, "alice-token", "s")
        val b = AdvertiseIdentity.serviceUuid(namespace, "bob-token", "s")
        assertNotEquals(a, b)
    }

    @Test
    fun malformedNamespaceIsRefusedNotGuessed() {
        assertNull(AdvertiseIdentity.serviceUuid("", "p", "s"))
        assertNull(AdvertiseIdentity.serviceUuid("not-a-uuid", "p", "s"))
        assertNull(AdvertiseIdentity.serviceUuid("6d6f6e61-6461-4076-b100-00000000000", "p", "s"))
        assertNull(AdvertiseIdentity.serviceUuid("6d6f6e61-6461-4076-b100-00000000000g", "p", "s"))
    }

    @Test
    fun outputParsesBackAsAUuid() {
        val uuid = AdvertiseIdentity.serviceUuid(namespace, "participant-7", "session-a")!!
        assertNotNull(AdvertiseIdentity.parseUuid(uuid))
    }

    @Test
    fun foldStaysInSixteenBits() {
        for (value in listOf("", "a", "participant-7", "0e58f1c2-very-long-identifier-string")) {
            val key = AdvertiseIdentity.fold16(value)
            assertTrue(key in 0..0xFFFF, "fold16(\"$value\") = $key escapes 16 bits")
        }
    }
}
