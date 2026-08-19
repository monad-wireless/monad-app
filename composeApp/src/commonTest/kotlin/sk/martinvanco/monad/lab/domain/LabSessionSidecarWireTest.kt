package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sidecar is a published artefact, so its field names are a contract with the analysis side.
 *
 * Renaming a `@SerialName` is a silent change here and a loud one three months later, in a reader
 * that has already been pointed at a season of recordings. These assertions are on the *wire* names
 * rather than the Kotlin properties for exactly that reason.
 *
 * Scoped to the provenance and block-structure fields, which are the ones that just changed.
 */
class LabSessionSidecarWireTest {

    private val json = Json { encodeDefaults = true }

    private fun encode(sidecar: LabSessionSidecar): String =
        json.encodeToString(LabSessionSidecar.serializer(), sidecar)

    private fun sidecar(
        appVersion: String = "1.2.0",
        buildId: String = "1.2.0+5.g0940fc0b.dirty586d603e",
        blocks: Long = 6,
        blockOpenAtSessionEnd: Boolean = false,
    ) = LabSessionSidecar(
        identity = SessionIdentity(sessionId = "s", participantId = "p"),
        radio = SessionRadio(),
        environment = SessionEnvironment(appVersion = appVersion, buildId = buildId),
        lifecycle = SessionLifecycle(),
        summary = SessionSummary(blocks = blocks, blockOpenAtSessionEnd = blockOpenAtSessionEnd),
    )

    @Test
    fun theSchemaVersionSaysWhichContractThisIs() {
        // The version is not cosmetic. v4 told a reader that `app_version` means the version of the
        // actual build rather than the hand-maintained string v3 carried. v5 is the walk: a null
        // `pose_track` or `mesh` means "this session deliberately recorded none" — a v4 sidecar says
        // nothing at all about trajectories or geometry, and treating the two alike counts every older
        // walk as a walk whose tracker failed.
        assertEquals("monad-app/session-sidecar/v5", LabSessionSidecar.SCHEMA)
        assertTrue(encode(sidecar()).contains("\"schema\":\"monad-app/session-sidecar/v5\""))
    }

    @Test
    fun provenanceIsCarriedAsAppVersionAndBuildId() {
        val encoded = encode(sidecar())
        assertTrue(encoded.contains("\"app_version\":\"1.2.0\""), encoded)
        assertTrue(encoded.contains("\"build_id\":\"1.2.0+5.g0940fc0b.dirty586d603e\""), encoded)
    }

    @Test
    fun theBuildIdStartsWithTheVersionSoOneRecoversTheOther() {
        // Recovery relies on this: an interrupted session's sidecar reads `app_version` back out of
        // the persisted build id with `substringBefore('+')`.
        val sidecar = sidecar()
        assertEquals(sidecar.environment.appVersion, sidecar.environment.buildId.substringBefore('+'))
    }

    @Test
    fun blockStructureIsReadableWithoutDownloadingTheMarkerStream() {
        val encoded = encode(sidecar(blocks = 5, blockOpenAtSessionEnd = true))
        assertTrue(encoded.contains("\"blocks\":5"), encoded)
        assertTrue(encoded.contains("\"block_open_at_session_end\":true"), encoded)
    }

    @Test
    fun aReaderOfAnOlderSidecarStillParses() {
        // Both new fields are additive with defaults, so a v3 document is still readable — it just
        // cannot say which build wrote it, which is the honest answer for a v3 document.
        val legacy = """
            {"schema":"monad-app/session-sidecar/v3",
             "identity":{"session_id":"s","participant_id":"p"},
             "radio":{},"environment":{"app_version":"0.3.0-lab"},
             "lifecycle":{},"summary":{"blocks":4}}
        """.trimIndent()
        val parsed = Json { ignoreUnknownKeys = true }
            .decodeFromString(LabSessionSidecar.serializer(), legacy)
        assertEquals("", parsed.environment.buildId)
        assertEquals(4L, parsed.summary.blocks)
        assertEquals(false, parsed.summary.blockOpenAtSessionEnd)
    }
}
