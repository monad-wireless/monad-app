package sk.martinvanco.monad.lab.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.lab.domain.GroundTruthDirection
import sk.martinvanco.monad.lab.domain.GroundTruthEvent
import sk.martinvanco.monad.lab.domain.LabArtefact
import sk.martinvanco.monad.lab.domain.SessionStatus
import sk.martinvanco.monad.lab.domain.upload.ArtefactSink
import sk.martinvanco.monad.lab.domain.upload.PartTag
import sk.martinvanco.monad.lab.domain.upload.PartedUpload
import sk.martinvanco.monad.lab.domain.upload.RetryPolicy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The upload path, against a real schema.
 *
 * The rule this class exists to enforce is **upload-then-delete**: the app's older quest path
 * exported, posted, and dropped the local rows on the completion path regardless of what the server
 * said, so a failed upload discarded the only copy of a field session. Every test here is a
 * different way of asking "did anything get deleted that was not acknowledged?".
 *
 * The second property is ordering: streams first, sidecar last. The sidecar's presence in the object
 * store is what the reader side uses to decide a prefix is complete, so uploading it over a missing
 * stream would mark a partial session as whole.
 */
class LabSessionUploaderTest {

    private lateinit var database: Database
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: LabSessionRepository
    private lateinit var groundTruth: GroundTruthRepository
    private lateinit var users: UserRepository

    /** Every artefact the sink was asked to store, in order, with its bytes. */
    private val stored = mutableListOf<Pair<String, Int>>()

    /** Artefacts the sink refuses, to model a network that fails partway through a session. */
    private var failOn: MutableSet<String> = mutableSetOf()

    /** Parts the sink accepted, as `artefact#partNumber` to size. Order is send order. */
    private val storedParts = mutableListOf<Pair<String, Int>>()

    /** `artefact#partNumber` the sink refuses ONCE, to model a connection lost mid-transfer. */
    private var failPartOnce: MutableSet<String> = mutableSetOf()

    /** Multipart uploads that were opened, and whether each was completed or aborted. */
    private val partedOutcome = mutableMapOf<String, String>()

    private val sink = object : ArtefactSink {
        override suspend fun put(
            sessionId: String,
            participantId: String,
            artefact: String,
            content: ByteArray,
            contentType: String,
            token: String,
        ) {
            if (artefact in failOn) throw IllegalStateException("no route to host")
            stored += artefact to content.size
        }

        override suspend fun beginParts(
            sessionId: String,
            participantId: String,
            artefact: String,
            totalBytes: Long,
            contentType: String,
            token: String,
        ): PartedUpload {
            if (artefact in failOn) throw IllegalStateException("no route to host")
            partedOutcome[artefact] = "open"
            return PartedUpload(
                sessionId = sessionId,
                participantId = participantId,
                artefact = artefact,
                uploadId = "upload-$artefact",
                totalBytes = totalBytes,
                // The real store's floor. Kept here so the plan under test is the plan the field
                // uses, rather than one a smaller test constant would produce.
                minPartBytes = 5 * 1024 * 1024,
            )
        }

        override suspend fun putPart(
            upload: PartedUpload,
            number: Int,
            isLast: Boolean,
            content: ByteArray,
            token: String,
        ): PartTag {
            val key = "${upload.artefact}#$number"
            if (failPartOnce.remove(key)) throw IllegalStateException("the network connection was lost")
            storedParts += key to content.size
            return PartTag(number = number, etag = "\"etag-$number\"")
        }

        override suspend fun completeParts(upload: PartedUpload, tags: List<PartTag>, token: String) {
            partedOutcome[upload.artefact] = "completed:${tags.size}"
            stored += upload.artefact to upload.totalBytes.toInt()
        }

        override suspend fun abortParts(upload: PartedUpload, token: String) {
            partedOutcome[upload.artefact] = "aborted"
        }
    }

    private class FakeTally(
        private val receipt: GroundTruthIngestReceipt?,
    ) : RoomTallyGateway {
        var submitted: Int = 0
        override suspend fun tally(labSessionId: String, token: String?): GroundTruthTally? = null
        override suspend fun submit(
            events: List<GroundTruthEvent>,
            token: String?,
        ): GroundTruthIngestReceipt? {
            submitted += events.size
            return receipt
        }
    }

    @BeforeTest
    fun setUp() {
        val (db, jdbc) = inMemoryDatabase()
        database = db
        driver = jdbc
        repository = LabSessionRepository(database)
        groundTruth = GroundTruthRepository(database)
        users = UserRepository(database)
        stored.clear()
        storedParts.clear()
        partedOutcome.clear()
        failOn = mutableSetOf()
        failPartOnce = mutableSetOf()
        runBlocking { users.insertUser("backend-1", "op@example.org", "Operator", "token-1") }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun uploader(
        tally: RoomTallyGateway = FakeTally(GroundTruthIngestReceipt(accepted = 1)),
    ) = LabSessionUploader(
        repository = repository,
        sink = sink,
        users = users,
        groundTruth = groundTruth,
        tallyService = tally,
        // One attempt: the backoff itself is covered by RetryPolicyTest, and four attempts here
        // would spend thirteen real seconds per failing artefact.
        retry = RetryPolicy(maxAttempts = 1),
    )

    private fun closedSession(id: String = "s-1") = runBlocking {
        repository.open(
            sessionId = id,
            participantId = "p-1",
            enrollmentId = null,
            questId = null,
            site = "fiit-library",
            apId = "ap-1",
            profileId = "prof-1",
            startedWallMillis = 1_700_000_000_000L,
            startedMonotonicNanos = 1_000,
            boundInterface = "wlan0",
            socketPinned = true,
            bootId = "epoch-1",
        )
        repository.appendClock(
            id,
            sk.martinvanco.monad.lab.domain.ClockEstimate(1_000, 2_000, 0.0, 5_000, 8),
        )
        repository.appendMarker(
            id,
            sk.martinvanco.monad.lab.domain.SessionMarker(
                kind = sk.martinvanco.monad.lab.domain.SessionMarker.Kind.BLOCK_START,
                label = "ZONE-A L4 seated staircase start",
                stepId = "blk-1",
                payload = "{}",
                monotonicNanos = 2_000,
                wallMillis = 1_700_000_001_000L,
            ),
        )
        repository.appendHealthCheckpoint(
            id, 3_000, 1_700_000_002_000L,
            sk.martinvanco.monad.lab.domain.health.InstrumentHealth(
                streams = listOf(
                    sk.martinvanco.monad.lab.domain.health.StreamHealth(
                        stream = sk.martinvanco.monad.lab.domain.health.LabStream.ILLUMINATOR,
                        state = sk.martinvanco.monad.lab.domain.health.StreamState.ALIVE,
                        eventsPerSecond = 100.0,
                        expectedRateHz = 100.0,
                        totalEvents = 1_000,
                        silenceMillis = 0,
                        everProduced = true,
                        worstState = sk.martinvanco.monad.lab.domain.health.StreamState.ALIVE,
                        millisDegraded = 0,
                        millisStale = 0,
                        millisDead = 0,
                    )
                ),
            ),
        )
        repository.close(id, 1_700_000_100_000L, 100_000, """{"schema":"test"}""")
    }

    private fun scan(nonce: String) = GroundTruthEvent(
        labSessionId = "lab-1",
        participantToken = "p-1",
        zoneId = "ZONE-A",
        direction = GroundTruthDirection.IN,
        site = "fiit-library",
        monotonicNanos = 1_000,
        wallMillis = 1_700_000_000_000L,
        scanNonce = nonce,
        recordingSessionId = "s-1",
    )

    // ---- ordering and completeness ----------------------------------------------------------

    @Test
    fun everyStreamGoesUpBeforeTheSidecar() {
        closedSession()
        val report = runBlocking { uploader().flush(purgeAfter = false) }

        val artefacts = stored.map { it.first }
        assertEquals(
            listOf(
                LabArtefact.TRAFFIC,
                LabArtefact.BEACONS,
                LabArtefact.TRANSITIONS,
                LabArtefact.CLOCK,
                LabArtefact.MARKERS,
                LabArtefact.HEALTH,
                LabArtefact.POSE,
                LabArtefact.MESH_LOG,
                LabArtefact.LOG,
                LabArtefact.SIDECAR,
            ),
            artefacts,
            "the sidecar's presence is what marks a prefix complete — it must be last",
        )
        assertEquals(1, report.sessionsUploaded)
        assertTrue(report.isClean, report.headline)
    }

    @Test
    fun healthIsShippedAsItsOwnArtefact() {
        // The sidecar carries health at close; this is the trace through the session, which is what
        // lets an analysis exclude a degraded interval rather than a whole session.
        closedSession()
        runBlocking { uploader().flush(purgeAfter = false) }

        val health = assertNotNull(stored.firstOrNull { it.first == LabArtefact.HEALTH })
        assertTrue(health.second > 0, "an empty health artefact would be indistinguishable from none")
        assertTrue(
            runBlocking { repository.healthTsv("s-1") }.decodeToString().startsWith("mono_ns\t"),
        )
    }

    @Test
    fun blockMarkersReachTheMarkersArtefact() {
        closedSession()
        val tsv = runBlocking { repository.markersTsv("s-1") }.decodeToString()
        assertTrue(tsv.contains("block_start"), tsv)
        // block_id travels in step_id, so a reader that only wants block boundaries never parses
        // the payload.
        assertTrue(tsv.contains("blk-1"), tsv)
        assertEquals(1L, runBlocking { repository.counts("s-1") }.blocks)
    }

    // ---- large artefacts go up in parts ------------------------------------------------------
    //
    // The regression these pin: on 2026-08-26 a 21-minute survey walk uploaded nine artefacts and
    // lost two. `mesh.ply` was 102.94 MB, the socket dropped mid-body, and all four retries
    // restarted the same doomed request. Meanwhile `mesh.tsv` — the 4 874-row observation log —
    // uploaded cleanly, so downstream read `mesh: False` as "this device has no LiDAR". It was an
    // iPhone 17 Pro.

    @Test
    fun aLargeArtefactIsCutIntoPartsAndSealedOnce() {
        closedSession()
        val bytes = ByteArray(20 * 1024 * 1024) { (it % 251).toByte() }
        runBlocking { repository.putBlob("s-1", "mesh.ply", "application/octet-stream", 1_000, 1L, bytes) }

        val report = runBlocking { uploader().flush(purgeAfter = false) }

        // 20 MiB at the 8 MiB part size: 8 + 8 + 4.
        assertEquals(
            listOf("mesh.ply#1" to 8 * 1024 * 1024, "mesh.ply#2" to 8 * 1024 * 1024, "mesh.ply#3" to 4 * 1024 * 1024),
            storedParts,
            "a short FINAL part is legal; a short interior one is not",
        )
        assertEquals("completed:3", partedOutcome["mesh.ply"])
        assertTrue(report.isClean, report.headline)
        assertEquals(1, report.sessionsUploaded)
    }

    @Test
    fun aSmallBlobStillGoesUpAsOneBody() {
        // The threshold is a stated boundary, not a preference. A `worldmap.armap` of a few hundred
        // kilobytes has no reason to spend three round trips.
        closedSession()
        runBlocking {
            repository.putBlob("s-1", "worldmap.armap", "application/octet-stream", 1_000, 1L, ByteArray(4_096))
        }

        runBlocking { uploader().flush(purgeAfter = false) }

        assertTrue(storedParts.isEmpty(), "a 4 KiB artefact must not open a multipart upload")
        assertEquals(null, partedOutcome["worldmap.armap"])
        assertEquals(4_096, stored.first { it.first == "worldmap.armap" }.second)
    }

    @Test
    fun aLostConnectionCostsONEPartAndNotTheWholeArtefact() {
        // THE regression. Before the parted path, one dropped socket at 60 MB re-sent all 103 MB —
        // four times — and then lost the artefact. Here part 2 fails once, is re-sent alone, and
        // the artefact completes.
        closedSession()
        runBlocking {
            repository.putBlob(
                "s-1",
                "mesh.ply",
                "application/octet-stream",
                1_000,
                1L,
                ByteArray(20 * 1024 * 1024),
            )
        }
        failPartOnce = mutableSetOf("mesh.ply#2")

        val report = runBlocking {
            LabSessionUploader(
                repository = repository,
                sink = sink,
                users = users,
                groundTruth = groundTruth,
                tallyService = FakeTally(GroundTruthIngestReceipt(accepted = 1)),
                // Two attempts per PART. The point is that the budget is spent per part, so one
                // retry rescues the artefact instead of restarting it.
                retry = RetryPolicy(maxAttempts = 2, baseDelayMillis = 0),
            ).flush(purgeAfter = false)
        }

        assertEquals(
            listOf("mesh.ply#1", "mesh.ply#2", "mesh.ply#3"),
            storedParts.map { it.first },
            "part 1 must not be re-sent because part 2 failed",
        )
        assertEquals("completed:3", partedOutcome["mesh.ply"])
        assertTrue(report.isClean, report.headline)
    }

    @Test
    fun aPartThatExhaustsItsBudgetAbortsTheUploadAndRetainsEveryByte() {
        closedSession()
        runBlocking {
            repository.putBlob(
                "s-1",
                "mesh.ply",
                "application/octet-stream",
                1_000,
                1L,
                ByteArray(20 * 1024 * 1024),
            )
        }
        // Fails on every attempt: the set is only consumed on success, so re-adding is not needed —
        // one attempt is configured by `uploader()`.
        failPartOnce = mutableSetOf("mesh.ply#1")

        val report = runBlocking { uploader().flush(purgeAfter = false) }

        assertEquals(
            "aborted",
            partedOutcome["mesh.ply"],
            "abandoned parts stay in the store, are billed, and appear in no listing — the abort " +
                "is protocol, not tidy-up",
        )
        assertFalse(report.isClean)
        assertEquals(0, report.sessionsUploaded)
        // Upload-then-delete still holds, and the sidecar was never sent over a missing artefact.
        assertEquals(
            SessionStatus.FAILED,
            SessionStatus.fromStorage(runBlocking { repository.byId("s-1") }?.status),
        )
        assertTrue(stored.none { it.first == LabArtefact.SIDECAR })
        assertEquals(1L, runBlocking { repository.counts("s-1") }.blobs)
        assertEquals(0, report.discarded)
    }

    @Test
    fun onePartIsReadAtATimeRatherThanTheWholeArtefact() {
        // Not a style point: `mesh.ply` was 102.94 MB and this phone is also running ARKit and the
        // camera. Every part the sink saw must be at most the part size — a single 20 MiB body
        // arriving here would mean the artefact had been materialised whole to cut it up.
        closedSession()
        runBlocking {
            repository.putBlob(
                "s-1",
                "mesh.ply",
                "application/octet-stream",
                1_000,
                1L,
                ByteArray(20 * 1024 * 1024) { (it % 97).toByte() },
            )
        }

        runBlocking { uploader().flush(purgeAfter = false) }

        assertTrue(storedParts.all { it.second <= PartedUpload.PART_BYTES }, storedParts.toString())
        assertEquals(20 * 1024 * 1024, storedParts.sumOf { it.second }, "every byte must reach the store once")
    }

    // ---- upload-then-delete ------------------------------------------------------------------

    @Test
    fun aFullyUploadedSessionIsPurged() {
        closedSession()
        runBlocking { uploader().flush(purgeAfter = true) }

        assertEquals(null, runBlocking { repository.byId("s-1") })
        val counts = runBlocking { repository.counts("s-1") }
        assertEquals(0L, counts.markers)
        assertEquals(0L, counts.health)
        assertEquals(0L, counts.clock)
    }

    @Test
    fun aFailureRetainsEveryByteAndNeverUploadsTheSidecar() {
        closedSession()
        failOn = mutableSetOf(LabArtefact.MARKERS)

        val report = runBlocking { uploader().flush(purgeAfter = true) }

        assertFalse(stored.any { it.first == LabArtefact.SIDECAR }, "a partial prefix must not be sealed")
        assertFalse(stored.any { it.first == LabArtefact.HEALTH }, "it stops at the first failure")
        val row = assertNotNull(runBlocking { repository.byId("s-1") }, "the session must not be deleted")
        assertEquals(SessionStatus.FAILED, SessionStatus.fromStorage(row.status))
        assertEquals(1L, runBlocking { repository.counts("s-1") }.markers, "the rows are still here")
        assertEquals(0, report.sessionsUploaded)
        assertEquals(0, report.discarded)
        assertTrue(report.headline.contains("Nothing was discarded"), report.headline)
    }

    @Test
    fun aFailedSessionIsRetriedOnTheNextFlushAndThenPurged() {
        closedSession()
        failOn = mutableSetOf(LabArtefact.CLOCK)
        runBlocking { uploader().flush(purgeAfter = true) }
        assertEquals(
            SessionStatus.FAILED,
            SessionStatus.fromStorage(assertNotNull(runBlocking { repository.byId("s-1") }).status),
        )

        // `selectPendingUpload` takes `failed` as well as `closed`, which is what makes a retry a
        // retry rather than a manual rescue.
        failOn = mutableSetOf()
        stored.clear()
        val second = runBlocking { uploader().flush(purgeAfter = true) }

        assertEquals(1, second.sessionsUploaded)
        assertEquals(null, runBlocking { repository.byId("s-1") })
    }

    @Test
    fun aSessionWithNoSidecarIsFailedRatherThanUploadedHalfway() {
        runBlocking {
            repository.open(
                sessionId = "s-open", participantId = "p", enrollmentId = null, questId = null,
                site = null, apId = null, profileId = null,
                startedWallMillis = 1, startedMonotonicNanos = 1,
                boundInterface = null, socketPinned = false, bootId = "epoch-1",
            )
            // Reach `pendingUpload` without ever having been closed properly.
            repository.markFailed("s-open", "earlier attempt")
        }

        runBlocking { uploader().flush(purgeAfter = true) }

        assertTrue(stored.isEmpty())
        assertNotNull(runBlocking { repository.byId("s-open") })
    }

    // ---- nothing-to-send vs everything-failed -----------------------------------------------

    @Test
    fun nothingToSendIsDistinguishableFromEverythingFailed() {
        // The old flush returned an integer, and both cases are zero. They call for opposite
        // actions, so the report has to be able to tell them apart.
        val empty = runBlocking { uploader().flush() }
        assertTrue(empty.didNothing)
        assertTrue(empty.headline.contains("Nothing to send"), empty.headline)

        closedSession()
        failOn = mutableSetOf(LabArtefact.TRAFFIC)
        val failed = runBlocking { uploader().flush() }
        assertFalse(failed.didNothing)
        assertTrue(failed.artefactsFailed > 0)
    }

    @Test
    fun anUnauthenticatedFlushSkipsRatherThanFailing() {
        runBlocking { users.deleteAllUsers() }
        closedSession()

        val report = runBlocking { uploader().flush() }

        assertEquals("you are not signed in", report.skippedReason)
        assertTrue(stored.isEmpty())
        assertNotNull(runBlocking { repository.byId("s-1") })
    }

    // ---- ground truth and E3 -----------------------------------------------------------------

    @Test
    fun groundTruthDrainsOnTheSameTriggerAndIsNeverDeleted() {
        closedSession()
        runBlocking { groundTruth.record(scan("nonce-1")) }

        val report = runBlocking { uploader().flush(purgeAfter = true) }

        assertEquals(1, report.groundTruthFilesSent)
        assertTrue(stored.any { it.first == LabArtefact.GROUND_TRUTH })
        // Marked sent, kept forever: a few dozen rows that are the only record a *person* was in
        // the room.
        assertEquals(1, runBlocking { groundTruth.eventsForSession("lab-1") }.size)
        assertEquals(0L, runBlocking { groundTruth.pendingCount() })
    }

    @Test
    fun anE3ConflictReachesTheFlushReportAndItsHeadline() {
        // By analysis time the affected interval is already excluded and there is nobody left to
        // ask what happened. The receipt is the first and only moment a conflict is knowable.
        closedSession()
        runBlocking { groundTruth.record(scan("nonce-1")) }
        val tally = FakeTally(GroundTruthIngestReceipt(accepted = 0, conflicts = 1))

        val report = runBlocking { uploader(tally).flush(purgeAfter = true) }

        assertEquals(1, report.tally.conflicts)
        assertTrue(report.tally.hasConflicts)
        assertFalse(report.isClean, "a conflict must not be able to pass as a clean flush")
        assertTrue(report.headline.contains("CONFLICT"), report.headline)
    }

    @Test
    fun anUnreachableRoomTallyNeverEndangersTheDatasetCopy() {
        closedSession()
        runBlocking { groundTruth.record(scan("nonce-1")) }

        val report = runBlocking { uploader(FakeTally(null)).flush(purgeAfter = true) }

        assertTrue(report.tally.unreachable)
        // The S3 artefact — the science — went up regardless, and the scans stay queued for the
        // aggregate, which is an operational convenience.
        assertEquals(1, report.groundTruthFilesSent)
        assertEquals(1L, runBlocking { groundTruth.pendingIngestCount() })
        assertEquals(
            null,
            runBlocking { repository.byId("s-1") },
            "and the session still purged: the aggregate may never gate the artefact",
        )
    }

    @Test
    fun rowsTheServerHasSeenAreNotResentForever() {
        closedSession()
        runBlocking { groundTruth.record(scan("nonce-1")) }
        val tally = FakeTally(GroundTruthIngestReceipt(duplicates = 1))

        runBlocking { uploader(tally).flush(purgeAfter = true) }
        val afterFirst = tally.submitted
        runBlocking { uploader(tally).flush(purgeAfter = true) }

        assertEquals(afterFirst, tally.submitted, "an acknowledged batch is not resubmitted")
    }

    // ---- inventory ---------------------------------------------------------------------------

    @Test
    fun thePendingInventoryBreaksDownByArtefactIncludingHealth() {
        closedSession()
        runBlocking { groundTruth.record(scan("nonce-1")) }

        val inventory = runBlocking { uploader().pendingInventory() }

        assertEquals(1, inventory.sessionCount)
        val names = inventory.byArtefact().map { it.artefact }
        assertTrue(LabArtefact.HEALTH in names, names.toString())
        assertTrue(LabArtefact.MARKERS in names, names.toString())
        assertEquals(1L, inventory.groundTruthRows)
    }
}
