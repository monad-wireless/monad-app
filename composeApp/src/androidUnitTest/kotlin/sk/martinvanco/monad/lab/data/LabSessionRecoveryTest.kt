package sk.martinvanco.monad.lab.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.lab.domain.ClockGateStatus
import sk.martinvanco.monad.lab.domain.LabSessionSidecar
import sk.martinvanco.monad.lab.domain.SessionStatus
import sk.martinvanco.monad.lab.domain.clockBootId
import sk.martinvanco.monad.lab.domain.health.InstrumentHealth
import sk.martinvanco.monad.lab.domain.health.LabStream
import sk.martinvanco.monad.lab.domain.health.StreamHealth
import sk.martinvanco.monad.lab.domain.health.StreamState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Crash / kill / reboot recovery, against a real schema.
 *
 * The continuity-epoch guard is the highest-consequence logic in the lab path and was covered by
 * reasoning alone. Two things can go wrong with it, and both are silent:
 *
 * - **Too eager** — recovery closes the session that is running right now, mid-recording, and hands
 *   it to the uploader. The participant's phone is in their pocket and nothing on screen says so.
 * - **Too timid** — a stranded session stays `open` forever, invisible to `selectPendingUpload`,
 *   with every byte on disk and no path off the device.
 *
 * The third property is about honesty rather than correctness: `mono_ns` resets on reboot, so an
 * end stamped from the current epoch would weld two timelines together in the column the
 * pre-registration treats as the authoritative join key.
 */
class LabSessionRecoveryTest {

    private lateinit var database: Database
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: LabSessionRepository
    private lateinit var recovery: LabSessionRecovery

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        val (db, jdbc) = inMemoryDatabase()
        database = db
        driver = jdbc
        repository = LabSessionRepository(database)
        recovery = LabSessionRecovery(repository)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun openSession(id: String, bootId: String?, startedMono: Long = 5_000_000_000L) =
        runBlocking {
            repository.open(
                sessionId = id,
                participantId = "p-1",
                enrollmentId = null,
                questId = null,
                site = "fiit-library",
                apId = "ap-1",
                profileId = "prof-1",
                startedWallMillis = 1_700_000_000_000L,
                startedMonotonicNanos = startedMono,
                boundInterface = "wlan0",
                socketPinned = true,
                bootId = bootId.orEmpty(),
            )
            if (bootId == null) {
                // A row written by a build from before epochs were recorded.
                driver.execute(
                    null,
                    "UPDATE LabSessionRecord SET bootId = NULL WHERE sessionId = '$id'",
                    0,
                )
            }
        }

    private fun sidecarOf(sessionId: String): LabSessionSidecar = runBlocking {
        val encoded = assertNotNull(repository.byId(sessionId)?.sidecarJson, "no sidecar written")
        json.decodeFromString(LabSessionSidecar.serializer(), encoded)
    }

    private fun health(
        state: StreamState,
        worst: StreamState,
        degradedMillis: Long = 0,
        events: Long = 100,
    ) = InstrumentHealth(
        streams = listOf(
            StreamHealth(
                stream = LabStream.ILLUMINATOR,
                state = state,
                eventsPerSecond = 11.6,
                expectedRateHz = 100.0,
                totalEvents = events,
                silenceMillis = 0,
                everProduced = true,
                worstState = worst,
                millisDegraded = degradedMillis,
                millisStale = 0,
                millisDead = 0,
            )
        ),
    )

    // ---- the guard ------------------------------------------------------------------------

    @Test
    fun aSessionFromThisEpochIsNeverTouched() {
        // This is the session running right now: the process that opened it is the process asking.
        // Recovery runs from screens a participant can open mid-recording, so getting this wrong
        // would close a live session and upload it.
        openSession("live", bootId = clockBootId())

        val recovered = runBlocking { recovery.recover() }

        assertTrue(recovered.isEmpty())
        val row = runBlocking { repository.byId("live") }
        assertEquals(SessionStatus.OPEN, SessionStatus.fromStorage(assertNotNull(row).status))
        assertNull(row.interruptedReason)
    }

    @Test
    fun aSessionFromAnEarlierEpochIsClosedAndMarkedInterrupted() {
        openSession("killed", bootId = "android-1699999999999-deadbeef")

        val recovered = runBlocking { recovery.recover() }

        assertEquals(1, recovered.size)
        assertEquals("killed", recovered.single().sessionId)
        val row = assertNotNull(runBlocking { repository.byId("killed") })
        assertEquals(SessionStatus.CLOSED, SessionStatus.fromStorage(row.status))
        assertNotNull(row.interruptedReason, "a truncated recording must not read as a quiet one")
    }

    @Test
    fun aRowWithNoRecordedEpochIsAssumedStranded() {
        openSession("legacy", bootId = null)

        val recovered = runBlocking { recovery.recover() }

        assertEquals(1, recovered.size)
        assertTrue(
            recovered.single().reason.contains("before this build recorded continuity epochs"),
            recovered.single().reason,
        )
    }

    @Test
    fun theEndIsNeverStampedOnAClockItDoesNotBelongTo() {
        openSession("killed", bootId = "android-1699999999999-deadbeef")
        runBlocking { recovery.recover() }

        val row = assertNotNull(runBlocking { repository.byId("killed") })
        assertNull(row.endedMonoNs, "mono_ns from this epoch is not on the session's timeline")
        assertFalse(sidecarOf("killed").lifecycle.monotonicContinuous)
    }

    @Test
    fun aRecoveredSessionBecomesVisibleToTheUploader() {
        // The bug this whole class exists for: `selectPendingUpload` only takes `closed` and
        // `failed`, so a stranded `open` row was complete on disk and invisible to every upload path.
        openSession("stranded", bootId = "other-epoch")
        assertTrue(runBlocking { repository.pendingUpload() }.isEmpty())

        runBlocking { recovery.recover() }

        assertEquals(
            listOf("stranded"),
            runBlocking { repository.pendingUpload() }.map { it.sessionId },
        )
    }

    @Test
    fun recoveryIsIdempotent() {
        openSession("killed", bootId = "other-epoch")
        assertEquals(1, runBlocking { recovery.recover() }.size)
        // The row is no longer `open`, so a second pass has nothing to find — a screen reopened
        // twice must not produce two recoveries of one session.
        assertTrue(runBlocking { recovery.recover() }.isEmpty())
    }

    @Test
    fun aMixOfLiveAndStrandedSessionsSeparatesCorrectly() {
        openSession("live", bootId = clockBootId())
        openSession("old-a", bootId = "epoch-a")
        openSession("old-b", bootId = "epoch-b")

        val recovered = runBlocking { recovery.recover() }.map { it.sessionId }.toSet()

        assertEquals(setOf("old-a", "old-b"), recovered)
        assertEquals(
            SessionStatus.OPEN,
            SessionStatus.fromStorage(assertNotNull(runBlocking { repository.byId("live") }).status),
        )
    }

    // ---- health across process death --------------------------------------------------------

    @Test
    fun healthSurvivesProcessDeath() {
        // The question the health module exists to answer — "was it degraded for 42 minutes?" — was
        // unanswerable for a killed session, which is exactly the session most likely to have gone
        // wrong. Checkpoints are what make it answerable.
        openSession("crashed", bootId = "other-epoch")
        runBlocking {
            repository.appendHealthCheckpoint(
                "crashed", 10_000_000_000L, 1_700_000_010_000L,
                health(StreamState.ALIVE, StreamState.ALIVE),
            )
            repository.appendHealthCheckpoint(
                "crashed", 2_530_000_000_000L, 1_700_002_530_000L,
                health(StreamState.DEGRADED, StreamState.DEGRADED, degradedMillis = 2_520_000),
            )
        }

        runBlocking { recovery.recover() }

        val sidecar = sidecarOf("crashed")
        val illuminator = assertNotNull(sidecar.health.firstOrNull { it.stream == "illuminator" })
        assertEquals("degraded", illuminator.worst)
        assertEquals(2_520_000L, illuminator.degradedMillis, "42 minutes, and it is on the record")
        assertEquals(2L, sidecar.summary.healthCheckpoints)
    }

    @Test
    fun theRecoveredEndIsTheLastMomentTheSessionWasObservablyAlive() {
        // Not `now`: recovery runs whenever somebody reopens the app, possibly days later, and
        // stamping that as the end would inflate the session's duration by the whole gap.
        openSession("crashed", bootId = "other-epoch")
        runBlocking {
            repository.appendHealthCheckpoint(
                "crashed", 2_530_000_000_000L, 1_700_002_530_000L,
                health(StreamState.ALIVE, StreamState.ALIVE),
            )
            recovery.recover()
        }

        assertEquals(1_700_002_530_000L, sidecarOf("crashed").lifecycle.endedWallMillis)
        assertTrue(
            sidecarOf("crashed").lifecycle.events.any { it.kind == "last_health_checkpoint" },
            "and the gap between that and the recovery is recorded, not hidden",
        )
    }

    @Test
    fun withoutACheckpointRecoveryReportsNoHealthRatherThanAHealthyOne() {
        // Null is not "healthy". A session killed inside its first thirty seconds has nothing to
        // report, and inventing a clean bill of health would be fabricating evidence.
        openSession("crashed-early", bootId = "other-epoch")

        val recovered = runBlocking { recovery.recover() }.single()

        assertTrue(sidecarOf("crashed-early").health.isEmpty())
        assertFalse(recovered.hasHealthHistory)
        assertNull(recovered.worstStreamState)
        assertTrue(
            sidecarOf("crashed-early").clockGate?.note.orEmpty()
                .contains("no health checkpoint survived"),
        )
    }

    @Test
    fun theClockGateVerdictIsCarriedForwardFromTheLastCheckpoint() {
        openSession("crashed", bootId = "other-epoch")
        runBlocking {
            repository.appendClock(
                "crashed",
                sk.martinvanco.monad.lab.domain.ClockEstimate(1_000, 2_000, 0.0, 5_000, 8),
            )
            repository.appendClock(
                "crashed",
                sk.martinvanco.monad.lab.domain.ClockEstimate(1_100, 2_100, 1.0, 65_000, 8),
            )
            repository.appendHealthCheckpoint(
                "crashed", 100_000L, 1_700_000_100L,
                health(StreamState.ALIVE, StreamState.ALIVE).copy(
                    clockGate = sk.martinvanco.monad.lab.domain.ClockGateReport(
                        status = ClockGateStatus.OK,
                        sampleCount = 2,
                        maxFitResidualMillis = 42.0,
                    ),
                ),
            )
            recovery.recover()
        }

        val gate = assertNotNull(sidecarOf("crashed").clockGate)
        assertEquals(ClockGateStatus.OK.wire, gate.status)
        assertEquals(42.0, assertNotNull(gate.maxFitResidualMillis), absoluteTolerance = 1e-9)
        assertFalse(gate.wouldFailGate)
    }

    @Test
    fun aCheckpointResidualPastTheSixSecondBudgetFailsTheGateOnRecoveryToo() {
        openSession("crashed", bootId = "other-epoch")
        runBlocking {
            repository.appendClock(
                "crashed",
                sk.martinvanco.monad.lab.domain.ClockEstimate(1_000, 2_000, 0.0, 5_000, 8),
            )
            repository.appendClock(
                "crashed",
                sk.martinvanco.monad.lab.domain.ClockEstimate(1_100, 2_100, 1.0, 65_000, 8),
            )
            repository.appendHealthCheckpoint(
                "crashed", 100_000L, 1_700_000_100L,
                health(StreamState.ALIVE, StreamState.ALIVE).copy(
                    clockGate = sk.martinvanco.monad.lab.domain.ClockGateReport(
                        status = ClockGateStatus.OK,
                        sampleCount = 2,
                        maxFitResidualMillis = 9_000.0,
                    ),
                ),
            )
            recovery.recover()
        }

        assertTrue(assertNotNull(sidecarOf("crashed").clockGate).wouldFailGate)
    }
}
