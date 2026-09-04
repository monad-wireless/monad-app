package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The descriptor is a wire contract with the backend's closed key set and with the dataset (IP-149).
 *
 * Three rules, each pinned: unknown is absent (a null field never reaches the wire, under ANY Json
 * instance, including the sidecar's `encodeDefaults = true`); the always-present blocks are present
 * even when empty (so `{}` reads as "nothing to say", not "old build"); and the key names are the
 * snake_case names the backend validates.
 */
class HandsetDescriptorWireTest {

    private val ios = HandsetDescriptor(
        handsetId = "0b6f2a8e-8c1d-4e2a-9f3b-1c2d3e4f5a6b",
        platform = "ios",
        machine = "iPhone15,2",
        manufacturer = "Apple",
        model = "iPhone",
        osVersion = "18.6",
        osBuild = "22G86",
        appVersion = "1.4.0",
        buildId = "1.4.0+41.g9a1d2f2b",
        capabilities = listOf("ble.advertise", "camera.qr"),
        sensors = listOf(SensorFact("barometer", available = true), SensorFact("uwb", available = false)),
        state = HandsetState(thermal = "nominal", lowPowerMode = false, batteryPct = 71),
    )

    @Test
    fun theKeysAreTheBackendsClosedSet() {
        val keys = Json.parseToJsonElement(ios.toJson()).jsonObject.keys
        assertEquals(
            setOf(
                "handset_id", "platform", "machine", "manufacturer", "model", "os_version", "os_build",
                "app_version", "build_id", "capabilities", "sensors", "radio", "state",
            ),
            keys,
        )
        assertFalse("soc" in keys, "iOS publishes no SoC name; the key must be absent, not null")
    }

    @Test
    fun unknownIsAbsentUnderTheSidecarsEncoderToo() {
        // The instrument's Json has encodeDefaults = true; @EncodeDefault(NEVER) must still drop nulls.
        val sidecarJson = Json { prettyPrint = true; encodeDefaults = true }
        val encoded = sidecarJson.encodeToString(HandsetDescriptor.serializer(), ios)
        assertFalse(encoded.contains("null"), encoded)
        assertFalse(encoded.contains("\"soc\""), encoded)
    }

    @Test
    fun emptyBlocksAreStatementsNotOmissions() {
        val encoded = ios.toJson()
        assertTrue(encoded.contains("\"radio\":{}"), encoded)
        val bare = HandsetDescriptor(handsetId = "x", platform = "android")
        val bareJson = bare.toJson()
        assertTrue(bareJson.contains("\"capabilities\":[]"), bareJson)
        assertTrue(bareJson.contains("\"sensors\":[]"), bareJson)
        assertTrue(bareJson.contains("\"state\":{}"), bareJson)
    }

    @Test
    fun aSensorFactCarriesOnlyWhatThePlatformSaid() {
        val encoded = ios.toJson()
        assertTrue(encoded.contains("{\"kind\":\"barometer\",\"available\":true}"), encoded)
        assertFalse(encoded.contains("\"vendor\""), "iOS has no vendor string; Android does")
    }

    @Test
    fun theSidecarCarriesTheDescriptorUnderEnvironment() {
        val sidecar = LabSessionSidecar(
            identity = SessionIdentity(sessionId = "s", participantId = "p"),
            radio = SessionRadio(),
            environment = SessionEnvironment(machine = "iPhone15,2", osBuild = "22G86", handset = ios),
            lifecycle = SessionLifecycle(),
            summary = SessionSummary(),
        )
        val encoded = Json { encodeDefaults = true }.encodeToString(LabSessionSidecar.serializer(), sidecar)
        val environment = Json.parseToJsonElement(encoded).jsonObject["environment"]!!.jsonObject
        assertEquals("iPhone15,2", environment["machine"].toString().trim('"'))
        assertEquals("22G86", environment["os_build"].toString().trim('"'))
        assertEquals("iPhone15,2", environment["handset"]!!.jsonObject["machine"].toString().trim('"'))
    }

    @Test
    fun aSessionNoQuestStartedCarriesNoHandsetKey() {
        val encoded = Json { encodeDefaults = true }.encodeToString(SessionEnvironment.serializer(), SessionEnvironment())
        assertFalse(encoded.contains("\"handset\""), encoded)
    }
}
