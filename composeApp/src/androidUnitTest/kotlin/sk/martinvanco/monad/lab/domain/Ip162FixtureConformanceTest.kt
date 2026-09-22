package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The IP-162 conformance fixtures, byte for byte.
 *
 * `src/androidUnitTest/resources/ip162/` is a verbatim copy of
 * `monad-knowledge/monad_knowledge/lab/contracts/fixtures/`. The Python package is the reference
 * reader; this app must reproduce its canonical bytes, its digests, its legacy classification and
 * every expected projection. A fixture that changes there fails here until the copy is refreshed,
 * which is the point: three repositories, one meaning.
 */
class Ip162FixtureConformanceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun theCopiedFixturesMatchTheirManifest() {
        val manifest = File(ROOT, "MANIFEST.sha256").readLines().filter { it.isNotBlank() }
            .associate { line -> line.substringAfter("  ") to line.substringBefore("  ") }
        assertTrue(manifest.isNotEmpty(), "empty manifest at ${File(ROOT).absolutePath}")
        val present = File(ROOT).walkTopDown().filter { it.isFile && it.name != "MANIFEST.sha256" }
            .associate { it.relativeTo(File(ROOT)).invariantSeparatorsPath to sha256(it.readBytes()) }
        val drift = (manifest.keys + present.keys).filter { manifest[it] != present[it] }.sorted()
        if (drift.isNotEmpty()) {
            fail(
                "fixture copy disagrees with MANIFEST.sha256 for: $drift. Refresh the copy from " +
                    "monad-knowledge/monad_knowledge/lab/contracts/fixtures/ rather than editing it here.",
            )
        }
    }

    @Test
    fun canonicalVectorsReproduce() {
        val doc = json.parseToJsonElement(File(ROOT, "canonical/vectors.json").readText()).jsonObject
        for (vector in doc.getValue("vectors").jsonArray) {
            val v = vector.jsonObject
            val name = v.getValue("name").jsonPrimitive.content
            assertEquals(v.getValue("canonical").jsonPrimitive.content, CanonicalJson.encode(v.getValue("input")), name)
            assertEquals(v.getValue("sha256").jsonPrimitive.content, CanonicalJson.sha256(v.getValue("input")), name)
        }
        for (rejected in doc.getValue("rejected").jsonArray) {
            val r = rejected.jsonObject
            val ok = runCatching { CanonicalJson.encode(r.getValue("input")) }.isSuccess
            assertTrue(!ok, "accepted a forbidden input: ${r["name"]}")
        }
    }

    @Test
    fun theKotlinDigestAgreesWithTheJvmDigestOnEveryFixtureFile() {
        // Sha256 is hand-written; the JVM's MessageDigest is the oracle it must agree with.
        File(ROOT).walkTopDown().filter { it.isFile }.forEach { file ->
            val bytes = file.readBytes()
            assertEquals(sha256(bytes), Sha256.hex(bytes), file.name)
        }
    }

    @Test
    fun legacyPayloadsClassifyByTheirSchemaStringAlone() {
        val cases = json.parseToJsonElement(File(ROOT, "legacy/payloads.json").readText()).jsonObject.getValue("cases").jsonArray
        for (case in cases) {
            val c = case.jsonObject
            val expected = c.getValue("expected").jsonObject
            val payload = c["payload"]
            val parsed = (payload as? JsonObject)?.let { HeadcountSweepEvent.fromJson(it) }
            val scope = expected.getValue("scope").jsonPrimitive.content
            when (scope) {
                // A v3 fixture case may be a fragment; the app parses only whole events, so the
                // one claim to check is that no legacy or unknown payload ever becomes a sweep event.
                "sweep" -> assertEquals("monad-app/headcount-marker/v3", payload!!.jsonObject.getValue("schema").jsonPrimitive.content)
                else -> assertNull(parsed, "${c["name"]}: a $scope payload must never parse as a sweep event")
            }
        }
    }

    @Test
    fun everyEventStreamProjectsToItsExpectedProjection() {
        val streams = listOf("valid", "invalid").flatMap { sub ->
            File(ROOT, "events/$sub").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        }
        assertTrue(streams.size >= 20, "expected the full fixture set, found ${streams.size}")
        for (file in streams) {
            val doc = json.parseToJsonElement(file.readText()).jsonObject
            val expected = json.parseToJsonElement(File(ROOT, "events/expected/${file.name}").readText())
                .jsonObject.getValue("projection").jsonObject
            val items = doc.getValue("events").jsonArray.map { item ->
                val env = item.jsonObject
                val mono = env.getValue("mono_ns").jsonPrimitive.content.toLong()
                mono to env.getValue("payload")
            }
            // Payloads that are not v3 events (legacy or malformed) are what the Python replay
            // refuses as not_a_sweep_event / schema_invalid; here they parse to null and are
            // replayed as a refused placeholder so the stream shape is preserved.
            val parsed = items.map { (mono, payload) -> mono to ((payload as? JsonObject)?.let { HeadcountSweepEvent.fromJson(it) }) }
            val state = HeadcountSweepReplay.replay(parsed.mapNotNull { (m, e) -> e?.let { m to it } })
            val unparseable = parsed.count { it.second == null }

            val where = file.name
            assertEquals(expected.getValue("phase").jsonPrimitive.content, state.phase.wire, "$where phase")
            assertEquals(expected.getValue("events_accepted").jsonPrimitive.int, state.eventsAccepted, "$where accepted")
            assertEquals(expected.getValue("duplicates_ignored").jsonPrimitive.int, state.duplicatesIgnored, "$where duplicates")
            assertEquals(expected["final_count"].intOrNullOf(), state.finalCount, "$where final_count")
            assertEquals(expected.getValue("corrections").jsonPrimitive.int, state.corrections, "$where corrections")
            assertEquals(expected["coverage"].stringOrNull(), state.coverage, "$where coverage")
            assertEquals(expected["occupancy_stability"].stringOrNull(), state.occupancyStability, "$where stability")
            assertEquals(expected["abort_reason"].stringOrNull(), state.abortReason, "$where abort_reason")
            assertEquals(expected["observers_in_area"].intOrNullOf(), state.observersInArea, "$where observers")
            assertEquals(expected["start_mono_ns"].stringOrNull()?.toLong(), state.startMonotonicNanos, "$where start_mono_ns")
            assertEquals(expected["end_mono_ns"].stringOrNull()?.toLong(), state.endMonotonicNanos, "$where end_mono_ns")

            val expectedCheckpoints = expected.getValue("checkpoints").jsonArray.map { cp ->
                val o = cp.jsonObject
                SweepCheckpoint(
                    eventId = o.getValue("event_id").jsonPrimitive.content,
                    sequence = o.getValue("sequence").jsonPrimitive.int,
                    count = o.getValue("count").jsonPrimitive.int,
                    monotonicNanos = o["mono_ns"].stringOrNull()?.toLong(),
                    correctedBy = o["corrected_by"].stringOrNull(),
                )
            }
            assertEquals(expectedCheckpoints, state.checkpoints, "$where checkpoints")

            // Error codes: the Python reader refuses non-v3 and schema-invalid payloads with a code;
            // this reader refuses them by not parsing. Compare the remaining codes exactly and the
            // total count including the unparseable ones.
            val expectedCodes = expected.getValue("errors").jsonArray.map { it.jsonObject.getValue("code").jsonPrimitive.content }
            val structural = setOf("not_a_sweep_event", "schema_invalid")
            assertEquals(expectedCodes.filterNot { it in structural }, state.errors.map { it.code }, "$where error codes")
            assertEquals(expectedCodes.count { it in structural }, unparseable, "$where unparseable payloads")
            assertEquals(expected.getValue("state").jsonPrimitive.content == "invalid", !state.isValid || unparseable > 0, "$where validity")
        }
    }

    @Test
    fun theValidManifestFixtureDigestsAsTheReferenceFixtureCites() {
        val manifest = json.parseToJsonElement(File(ROOT, "manifest/valid/sealed.json").readText()).jsonObject.getValue("manifest")
        val reference = json.parseToJsonElement(File(ROOT, "reference/valid/stable-room.json").readText())
            .jsonObject.getValue("reference").jsonObject
        assertEquals(reference.getValue("manifest_sha256").jsonPrimitive.content, CanonicalJson.sha256(manifest))
    }

    @Test
    fun theStudyFixtureIsFrozenOverItsOwnCanonicalBody() {
        val study = json.parseToJsonElement(File(ROOT, "study/valid/exp-f2-fixture.json").readText()).jsonObject.getValue("study").jsonObject
        val body = JsonObject(study.filterKeys { it != "frozen_sha256" })
        assertEquals(study.getValue("frozen_sha256").jsonPrimitive.content, CanonicalJson.sha256(body))
    }

    @Test
    fun theObserveConfigFixtureDigestsOverItsBodyWithoutTheDigestField() {
        val config = json.parseToJsonElement(File(ROOT, "observe/valid/room-sweep.json").readText()).jsonObject.getValue("config").jsonObject
        val body = JsonObject(config.filterKeys { it != "protocol_sha256" })
        assertEquals(config.getValue("protocol_sha256").jsonPrimitive.content, CanonicalJson.sha256(body))
    }

    private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement?.intOrNullOf(): Int? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Android unit tests run with the module directory as the working directory. */
        const val ROOT = "src/androidUnitTest/resources/ip162"
    }
}
