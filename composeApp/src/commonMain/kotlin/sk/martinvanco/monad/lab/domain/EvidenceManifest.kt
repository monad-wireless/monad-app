package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One uploaded artefact as the manifest names it: the bytes that went up, hashed as bytes.
 */
data class SealedArtefact(
    val name: String,
    val sha256: String,
    val bytes: Long,
    val contentType: String,
) {
    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "sha256" to JsonPrimitive(sha256),
            "bytes" to JsonPrimitive(bytes),
            "content_type" to JsonPrimitive(contentType),
        ),
    )
}

/**
 * `monad-lab/evidence-manifest/v1` — the sealed inventory of one recording's evidence (IP-162).
 *
 * Built by the uploader after every stream and blob has gone up and before the sidecar, from
 * three sources that already exist: the sidecar JSON the instrument closed the session with
 * (identity, build, clock domain, end stamps), the v3 events in the marker log (sweep ids, step
 * completion, payload schemas) and the digests of the artefact bytes it just uploaded. Its own
 * identity is the SHA-256 of its canonical JSON, which is what the backend's receipt and the
 * count-reference projection cite as `manifest_sha256`.
 *
 * Only a recording that carries at least one v3 event gets a manifest. A walk, a probe run or a
 * legacy Counting run is unchanged by this file.
 */
object EvidenceManifest {

    const val SCHEMA = "monad-lab/evidence-manifest/v1"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Assemble the manifest, or null when the recording carries no v3 sweep event or the sidecar
     * lacks an identity the schema requires. A null is "nothing to seal", never a partial seal.
     */
    fun build(
        sidecarJson: String,
        events: List<HeadcountSweepEvent>,
        payloadSchemas: Set<String>,
        artefacts: List<SealedArtefact>,
        snapshotSha256: String,
    ): JsonObject? {
        if (events.isEmpty()) return null
        val sidecar = runCatching { json.parseToJsonElement(sidecarJson).jsonObject }.getOrNull() ?: return null
        val identity = sidecar["identity"] as? JsonObject ?: return null
        val environment = sidecar["environment"] as? JsonObject ?: return null
        val lifecycle = sidecar["lifecycle"] as? JsonObject ?: return null

        val recordingSessionId = identity.string("session_id") ?: return null
        val enrollmentId = identity.string("enrollment_id")?.takeIf { it.isNotBlank() } ?: events.first().enrollmentId
        val questId = identity.string("quest_id")?.takeIf { it.isNotBlank() } ?: return null
        val stepCompletionId = events.first().stepCompletionId
        val sweepIds = events.map { it.sweepId }.distinct()

        val artefactArray = JsonArray(artefacts.sortedBy { it.name }.map { it.toJson() })
        val fields = linkedMapOf<String, JsonElement>(
            "schema" to JsonPrimitive(SCHEMA),
            "recording_session_id" to JsonPrimitive(recordingSessionId),
            "enrollment_id" to JsonPrimitive(enrollmentId),
            "quest_id" to JsonPrimitive(questId),
            "step_completion_id" to JsonPrimitive(stepCompletionId),
            "sweep_ids" to JsonArray(sweepIds.map { JsonPrimitive(it) }),
            "app_build_id" to JsonPrimitive(environment.string("build_id")?.takeIf { it.isNotBlank() } ?: "unknown"),
            "payload_schemas" to JsonArray(payloadSchemas.sorted().map { JsonPrimitive(it) }),
            "snapshot_sha256" to JsonPrimitive(snapshotSha256),
            "clock_domain" to JsonObject(
                mapOf(
                    "boot_id" to JsonPrimitive(lifecycle.string("boot_id")?.takeIf { it.isNotBlank() } ?: "unknown"),
                    "clock_source" to JsonPrimitive(environment.string("clock_source")?.takeIf { it.isNotBlank() } ?: "unknown"),
                    "monotonic_continuous" to JsonPrimitive(lifecycle["monotonic_continuous"]?.jsonPrimitive?.booleanOrNull ?: true),
                ),
            ),
            "artifacts" to artefactArray,
            // Nanoseconds cross JSON as decimal strings; milliseconds fit as integers.
            "sealed_mono_ns" to JsonPrimitive((lifecycle["ended_mono_ns"]?.jsonPrimitive?.longOrNull ?: 0L).toString()),
            "sealed_wall_ms" to JsonPrimitive(lifecycle["ended_wall_ms"]?.jsonPrimitive?.longOrNull ?: 0L),
        )
        return JsonObject(fields)
    }

    /** The bytes uploaded as `evidence-manifest.json`: canonical, so the digest is over what was sent. */
    fun encode(manifest: JsonObject): ByteArray = CanonicalJson.bytes(manifest)

    fun digest(manifest: JsonObject): String = CanonicalJson.sha256(manifest)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
}
