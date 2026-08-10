package sk.martinvanco.monad.lab.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import sk.martinvanco.monad.Database
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

    private companion object {
        /** Android unit tests run with the module directory as the working directory. */
        const val MIGRATIONS = "src/commonMain/sqldelight/migrations"
    }
}
