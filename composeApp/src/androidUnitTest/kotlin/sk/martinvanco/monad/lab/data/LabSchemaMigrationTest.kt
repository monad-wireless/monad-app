package sk.martinvanco.monad.lab.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.lab.domain.LabArtefact
import sk.martinvanco.monad.lab.domain.MeshObservation
import sk.martinvanco.monad.lab.domain.PoseSample
import sk.martinvanco.monad.lab.domain.TrackingQuality
import sk.martinvanco.monad.lab.domain.health.InstrumentHealth
import sk.martinvanco.monad.lab.domain.health.LabStream
import sk.martinvanco.monad.lab.domain.health.StreamHealth
import sk.martinvanco.monad.lab.domain.health.StreamState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The schema version and the migration that carries it.
 *
 * This exists because the previous pass found `sqldelight { version = 5 }` still live while
 * migrations 5 and 6 sat on disk: SQLDelight only runs migration `N.sqm` when `version > N`, so
 * neither could ever fire on an upgrade. Handsets already at schema 5 never received
 * `GroundTruthEventRecord` or `SessionMarkerRecord`, and the first check-in scan or session marker
 * would throw — on exactly the phones that had been deployed longest. Nothing failed at build time.
 *
 * Two properties are checked here, both of them about the *upgrade* path rather than a fresh
 * install, because a fresh install is the case that always worked.
 */
class LabSchemaMigrationTest {

    @Test
    fun theDeclaredVersionIsAheadOfEveryMigrationFile() {
        // Derived, not hardcoded. A literal here made this a chore that gets edited to whatever
        // makes the build green — which is the same reflex that let `version = 5` sit next to
        // 5.sqm and 6.sqm in the first place. The invariant is the rule SQLDelight actually
        // applies: `N.sqm` runs only when `version > N`, so the shipped version must be one
        // greater than the highest migration on disk.
        val highest = File(MIGRATIONS)
            .listFiles { file -> file.extension == "sqm" }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toLongOrNull() }
            .maxOrNull()
        assertNotNull(highest, "no migrations found at ${File(MIGRATIONS).absolutePath}")
        assertEquals(
            highest + 1,
            Database.Schema.version,
            "sqldelight { version } must be one ahead of the highest migration ($highest.sqm), " +
                "or that migration can never fire on an upgrade",
        )
    }

    @Test
    fun migratingOntoADatabaseThatAlreadyHasTheObjectsIsSafe() {
        // The upgrade path a deployed handset takes. Migration 9 must be idempotent, because it may
        // land on a database that already carries the table (a fresh install created it from
        // `LabSample.sq`) as well as on one that does not.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val result = Database.Schema.migrate(driver, 9, 10)
            assertTrue(result is QueryResult.Value<Unit> || result.value == Unit)

            // …and the table is genuinely usable afterwards, not merely present.
            val database = Database(driver)
            val repository = LabSessionRepository(database)
            runBlocking {
                repository.open(
                    sessionId = "s", participantId = "p", enrollmentId = null, questId = null,
                    site = null, apId = null, profileId = null,
                    startedWallMillis = 1, startedMonotonicNanos = 1,
                    boundInterface = null, socketPinned = false, bootId = "e",
                )
                repository.appendHealthCheckpoint(
                    "s", 1_000, 2_000,
                    InstrumentHealth(
                        streams = listOf(
                            StreamHealth(
                                stream = LabStream.WITNESS,
                                state = StreamState.ALIVE,
                                eventsPerSecond = 2.0,
                                expectedRateHz = null,
                                totalEvents = 7,
                                silenceMillis = 0,
                                everProduced = true,
                                worstState = StreamState.ALIVE,
                                millisDegraded = 0,
                                millisStale = 0,
                                millisDead = 0,
                            )
                        ),
                    ),
                )
                val checkpoint = assertNotNull(repository.lastHealthCheckpoint("s"))
                assertEquals(1, checkpoint.streams.size)
                assertEquals(7L, checkpoint.streams.single().events)
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun migration11GivesADeployedHandsetAUsablePoseTable() {
        // The upgrade a handset carrying schema 11 takes. `IF NOT EXISTS` is not enough on its own to
        // prove this: a table that exists and rejects an insert would pass a presence check and fail
        // on the first walk, in a corridor, with the operator already moving.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val result = Database.Schema.migrate(driver, 11, 12)
            assertTrue(result is QueryResult.Value<Unit> || result.value == Unit)

            val repository = LabSessionRepository(Database(driver))
            runBlocking {
                repository.open(
                    sessionId = "s", participantId = "p", enrollmentId = null, questId = null,
                    site = null, apId = null, profileId = null,
                    startedWallMillis = 1, startedMonotonicNanos = 1,
                    boundInterface = null, socketPinned = false, bootId = "e",
                )
                repository.appendPose(
                    "s",
                    listOf(
                        pose(monotonicNanos = 1_000, x = 0f, z = 0f),
                        pose(monotonicNanos = 2_000, x = 3f, z = 4f),
                    ),
                )
                val counts = repository.counts("s")
                assertEquals(2L, counts.pose)
                assertEquals(2L, counts.poseNormal)
                // 3-4-5: the reduction is a real sum over displacements, not a row count.
                assertEquals(5.0, repository.poseSummary("s").pathLengthMetres, 1e-6)
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun theExportedTrackCarriesQualityAndReasonPerRow() {
        // Without these two columns the analysis cannot drop the windows where the position was a
        // guess, and a track whose bad segments are unidentifiable has to be dropped whole.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val repository = LabSessionRepository(Database(driver))
            runBlocking {
                repository.open(
                    sessionId = "s", participantId = "p", enrollmentId = null, questId = null,
                    site = null, apId = null, profileId = null,
                    startedWallMillis = 1, startedMonotonicNanos = 1,
                    boundInterface = null, socketPinned = false, bootId = "e",
                )
                repository.appendPose(
                    "s",
                    listOf(
                        pose(monotonicNanos = 1_000, x = 0f, z = 0f),
                        pose(
                            monotonicNanos = 2_000, x = 1f, z = 0f,
                            quality = TrackingQuality.LIMITED, reason = "relocalizing",
                        ),
                    ),
                )
                val tsv = repository.poseTsv("s").decodeToString().trim().lines()
                assertEquals(
                    "mono_ns\twall_ms\tx_m\ty_m\tz_m\tqx\tqy\tqz\tqw\tquality\treason",
                    tsv.first(),
                )
                assertEquals(3, tsv.size)
                assertTrue(tsv[1].endsWith("\tnormal\t"), tsv[1])
                assertTrue(tsv[2].endsWith("\tlimited\trelocalizing"), tsv[2])
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun migration12GivesADeployedHandsetTheMeshLogAndABlobStore() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val result = Database.Schema.migrate(driver, 12, 13)
            assertTrue(result is QueryResult.Value<Unit> || result.value == Unit)

            val repository = LabSessionRepository(Database(driver))
            runBlocking {
                repository.open(
                    sessionId = "s", participantId = "p", enrollmentId = null, questId = null,
                    site = null, apId = null, profileId = null,
                    startedWallMillis = 1, startedMonotonicNanos = 1,
                    boundInterface = null, socketPinned = false, bootId = "e",
                )
                repository.appendMesh(
                    "s",
                    listOf(
                        mesh(monotonicNanos = 1_000, anchorId = "a", revision = 0, faces = 10),
                        mesh(monotonicNanos = 9_000, anchorId = "a", revision = 1, faces = 40),
                        mesh(monotonicNanos = 5_000, anchorId = "b", revision = 0, faces = 7),
                    ),
                )
                repository.putBlob(
                    sessionId = "s",
                    name = LabArtefact.MESH,
                    contentType = "application/octet-stream",
                    monotonicNanos = 9_500,
                    wallMillis = 20,
                    bytes = byteArrayOf(1, 2, 3, 4),
                )

                val counts = repository.counts("s")
                assertEquals(3L, counts.mesh)
                assertEquals(1L, counts.blobs)
                assertEquals(4L, counts.blobBytes)

                // Distinct blocks and the observation window — what the sidecar reports, computed without
                // materialising the log.
                val span = repository.meshSpan("s")
                assertEquals(2, span.anchors)
                assertEquals(1_000L, span.firstMonotonicNanos)
                assertEquals(9_000L, span.lastMonotonicNanos)
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun aReExportedBlobSupersedesRatherThanDuplicating() {
        // UNIQUE(sessionId, name) with INSERT OR REPLACE. Two meshes under one name would make the
        // uploaded prefix depend on row order, and a re-export is a correction rather than a second
        // observation.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val repository = LabSessionRepository(Database(driver))
            runBlocking {
                repository.open(
                    sessionId = "s", participantId = "p", enrollmentId = null, questId = null,
                    site = null, apId = null, profileId = null,
                    startedWallMillis = 1, startedMonotonicNanos = 1,
                    boundInterface = null, socketPinned = false, bootId = "e",
                )
                repository.putBlob("s", LabArtefact.MESH, "application/octet-stream", 1, 1, byteArrayOf(1))
                repository.putBlob(
                    "s", LabArtefact.MESH, "application/octet-stream", 2, 2, byteArrayOf(9, 9, 9),
                )

                val blobs = repository.blobs("s")
                assertEquals(1, blobs.size)
                assertEquals(3L, blobs.single().sizeBytes)
                assertEquals(3, repository.blobBytes("s", LabArtefact.MESH)?.size)
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun theMeshLogExportsInClockOrderWithTheJoinColumnFirst() {
        // `mono_ns` first, because that is the column the analysis joins on — and joining is the entire
        // purpose of this file. Ordered by it too: a change log out of order cannot be differenced.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val repository = LabSessionRepository(Database(driver))
            runBlocking {
                repository.open(
                    sessionId = "s", participantId = "p", enrollmentId = null, questId = null,
                    site = null, apId = null, profileId = null,
                    startedWallMillis = 1, startedMonotonicNanos = 1,
                    boundInterface = null, socketPinned = false, bootId = "e",
                )
                repository.appendMesh(
                    "s",
                    listOf(
                        mesh(monotonicNanos = 9_000, anchorId = "b", revision = 0, faces = 7),
                        mesh(monotonicNanos = 1_000, anchorId = "a", revision = 0, faces = 10),
                    ),
                )
                val lines = repository.meshTsv("s").decodeToString().trim().lines()
                assertEquals(
                    "mono_ns\twall_ms\tanchor_id\trevision\tvertices\tfaces\tclassified\t" +
                        "x_m\ty_m\tz_m",
                    lines.first(),
                )
                assertTrue(lines[1].startsWith("1000\t"), lines[1])
                assertTrue(lines[2].startsWith("9000\t"), lines[2])
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun purgingAnUploadedSessionTakesTheMeshWithIt() {
        // A mesh is the largest thing this app stores. Leaving it behind after the prefix is complete
        // would fill the device silently — and `purgeUploaded` is the only path allowed to delete, so a
        // table missing from it is a table that is never reclaimed.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val repository = LabSessionRepository(Database(driver))
            runBlocking {
                repository.open(
                    sessionId = "s", participantId = "p", enrollmentId = null, questId = null,
                    site = null, apId = null, profileId = null,
                    startedWallMillis = 1, startedMonotonicNanos = 1,
                    boundInterface = null, socketPinned = false, bootId = "e",
                )
                repository.appendMesh("s", listOf(mesh(1_000, "a", 0, 10)))
                repository.putBlob("s", LabArtefact.MESH, "application/octet-stream", 1, 1, byteArrayOf(1))
                repository.close("s", endedWallMillis = 2, endedMonotonicNanos = 2, sidecarJson = "{}")
                repository.markUploaded("s")

                assertTrue(repository.purgeUploaded("s"))
                assertEquals(0L, repository.counts("s").mesh)
                assertEquals(0L, repository.counts("s").blobs)
            }
        } finally {
            driver.close()
        }
    }

    private fun mesh(
        monotonicNanos: Long,
        anchorId: String,
        revision: Int,
        faces: Long,
    ) = MeshObservation(
        monotonicNanos = monotonicNanos,
        wallMillis = monotonicNanos / 1_000,
        anchorId = anchorId,
        revision = revision,
        vertices = faces * 3,
        faces = faces,
        classified = true,
        x = 1f,
        y = 2f,
        z = 3f,
    )

    private fun pose(
        monotonicNanos: Long,
        x: Float,
        z: Float,
        quality: TrackingQuality = TrackingQuality.NORMAL,
        reason: String? = null,
    ) = PoseSample(
        monotonicNanos = monotonicNanos,
        wallMillis = monotonicNanos / 1_000,
        x = x,
        y = 0f,
        z = z,
        qx = 0f,
        qy = 0f,
        qz = 0f,
        qw = 1f,
        quality = quality,
        reason = reason,
    )

    private companion object {
        /** Android unit tests run with the module directory as the working directory. */
        const val MIGRATIONS = "src/commonMain/sqldelight/migrations"
    }
}
