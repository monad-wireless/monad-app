package sk.martinvanco.monad.lab.domain

import sk.martinvanco.monad.lab.domain.health.StreamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The session-complete summary — the thing an operator screenshots.
 *
 * Derived from the sidecar and only from the sidecar, so what the operator reads and what the
 * collection side receives cannot disagree.
 */
class SessionReportTest {

    private fun sidecar(
        roles: List<String> = listOf("illuminator", "witness", "subject"),
        pinned: Boolean = true,
        residency: List<String> = listOf("always_authorization=ok (granted)"),
        health: List<StreamHealthRecord> = emptyList(),
        gate: ClockGateRecord? = ClockGateRecord(
            status = ClockGateStatus.OK.wire,
            samples = 4,
            meetsMinimumSamples = true,
            skewPpm = 2.5,
            maxFitResidualMillis = 0.4,
        ),
        markers: Long = 7,
        interrupted: String? = null,
        // A console session with three complete blocks. The default used to be a session with no
        // block edges at all, which — now that the report has an opinion about that — was a
        // *broken* session masquerading as the nominal fixture.
        questId: String = "",
        blocks: Long = 6,
        blockOpenAtSessionEnd: Boolean = false,
    ) = LabSessionSidecar(
        identity = SessionIdentity(
            sessionId = "0d2f9d70-4f6b-4a1a-9d6a-2f4b6c8e1a03",
            participantId = "p1",
            questId = questId,
            site = "fiit-library",
            roles = roles,
        ),
        radio = SessionRadio(boundInterface = "en0", socketPinned = pinned),
        environment = SessionEnvironment(residencyChecks = residency),
        lifecycle = SessionLifecycle(
            startedWallMillis = 1_000_000,
            endedWallMillis = 1_000_000 + 1_830_000,
            status = "closed",
            interruptedReason = interrupted,
        ),
        summary = SessionSummary(
            packetsSent = 180_000,
            beaconObservations = 900,
            markers = markers,
            blocks = blocks,
            blockOpenAtSessionEnd = blockOpenAtSessionEnd,
        ),
        health = health,
        clockGate = gate,
    )

    @Test
    fun aCleanSessionReadsAsNominal() {
        val report = SessionReport.from(sidecar())
        assertEquals(SessionReport.Level.GOOD, report.worstLevel)
        assertTrue(report.isUsable)
        assertTrue(report.headline.contains("nominal"))
        assertEquals("0d2f9d70", report.shortId)
        assertEquals(1_830_000L, report.durationMillis)
    }

    @Test
    fun anUnpinnedSocketIsTheWorstFailureAndSaysSo() {
        // The UI says connected, the datagrams leave over cellular, the observer sees nothing.
        val report = SessionReport.from(sidecar(pinned = false))
        val verdict = report.verdicts.first { it.label == "Socket binding" }
        assertEquals(SessionReport.Level.BAD, verdict.level)
        assertTrue(verdict.detail.contains("NOT PINNED"))
        assertFalse(report.isUsable)
    }

    @Test
    fun aWitnessOnlySessionIsNotBlamedForHavingNoSocket() {
        val report = SessionReport.from(sidecar(roles = listOf("witness", "subject"), pinned = false))
        val verdict = report.verdicts.first { it.label == "Socket binding" }
        assertEquals(SessionReport.Level.GOOD, verdict.level)
    }

    @Test
    fun missingResidencyIsFatalToTheSession() {
        val report = SessionReport.from(
            sidecar(residency = listOf("always_authorization=MISSING (when-in-use only)"))
        )
        val verdict = report.verdicts.first { it.label == "Background residency" }
        assertEquals(SessionReport.Level.BAD, verdict.level)
    }

    @Test
    fun aClockGateThatWouldExcludeTheFoldIsReportedAsAFailure() {
        val report = SessionReport.from(
            sidecar(
                gate = ClockGateRecord(
                    status = ClockGateStatus.OFFSET_ONLY.wire,
                    samples = 1,
                    meetsMinimumSamples = false,
                    wouldFailGate = true,
                )
            )
        )
        val verdict = report.verdicts.first { it.label == "Clock gate G4" }
        assertEquals(SessionReport.Level.BAD, verdict.level)
        assertTrue(verdict.detail.contains("excluded") || verdict.detail.contains("flagged"))
    }

    @Test
    fun aStreamThatRecoveredIsStillReportedAsHavingHadTrouble() {
        // The 42-minute question: healthy at the end is not the same as healthy throughout.
        val report = SessionReport.from(
            sidecar(
                health = listOf(
                    StreamHealthRecord(
                        stream = "illuminator",
                        state = StreamState.ALIVE.wire,
                        worst = StreamState.DEGRADED.wire,
                        events = 180_000,
                        deliveredFraction = 0.116,
                        degradedMillis = 2_520_000,
                    )
                )
            )
        )
        val verdict = report.verdicts.first { it.label == "illuminator" }
        assertEquals(SessionReport.Level.WARN, verdict.level)
        assertTrue(verdict.detail.contains("degraded"), verdict.detail)
        assertTrue(verdict.detail.contains("42m"), "duration must be legible: ${verdict.detail}")
    }

    @Test
    fun aSessionWithNoMarkersIsFlaggedAsHavingNoTakeStructure() {
        val report = SessionReport.from(sidecar(markers = 0))
        assertTrue(report.verdicts.any { it.label == "Markers" && it.level == SessionReport.Level.WARN })
    }

    @Test
    fun aConsoleSessionWithNoBlocksIsUnusable() {
        // The failure the operator cannot fix afterwards: nothing in the recording says which part
        // was which condition, and the label IS the ground truth the analysis uses. It has to move
        // the whole verdict, not sit as a footnote — an operator who forgot to mark previously got
        // a green card.
        val report = SessionReport.from(sidecar(markers = 4, blocks = 0))
        val verdict = report.verdicts.first { it.label == "Block labels" }
        assertEquals(SessionReport.Level.BAD, verdict.level)
        assertTrue(verdict.detail.startsWith("NONE"), verdict.detail)
        assertFalse(report.isUsable)
        assertEquals(SessionReport.Level.BAD, report.worstLevel)
    }

    @Test
    fun aParticipantQuestRunIsNotJudgedOnBlocks() {
        // Blocks are written from the lab console only; a quest run's take structure is its step
        // markers. Failing every participant session for lacking blocks would train the fleet to
        // ignore this card.
        val report = SessionReport.from(sidecar(questId = "q-17", blocks = 0))
        assertTrue(report.verdicts.none { it.label == "Block labels" })
        assertEquals(SessionReport.Level.GOOD, report.worstLevel)
    }

    @Test
    fun aBlockLeftOpenAtTheEndIsRecoverableAndSaysWhy() {
        // The auto-close writes a real edge carrying stop_reason=session_end, so the boundary is
        // knowable — it just is not a judgement that the condition was over.
        val report = SessionReport.from(sidecar(blocks = 6, blockOpenAtSessionEnd = true))
        val verdict = report.verdicts.first { it.label == "Block labels" }
        assertEquals(SessionReport.Level.WARN, verdict.level)
        assertTrue(verdict.detail.contains("closed by the session ending"), verdict.detail)
        assertTrue(report.isUsable)
    }

    @Test
    fun aBlockWithNoClosingEdgeAtAllIsWorseThanAnAutoClose() {
        // Odd edge count: the process died with a block open, so that block has no trailing edge
        // in the stream at all. Bounded damage — the blocks before it are intact.
        val report = SessionReport.from(sidecar(blocks = 5, interrupted = "interrupted by an OS kill"))
        val verdict = report.verdicts.first { it.label == "Block labels" }
        assertEquals(SessionReport.Level.WARN, verdict.level)
        assertTrue(verdict.detail.contains("never closed"), verdict.detail)
    }

    @Test
    fun aCompleteConsoleSessionReportsItsBlockCount() {
        val report = SessionReport.from(sidecar(blocks = 6))
        val verdict = report.verdicts.first { it.label == "Block labels" }
        assertEquals(SessionReport.Level.GOOD, verdict.level)
        assertTrue(verdict.detail.startsWith("3 block(s)"), verdict.detail)
    }

    @Test
    fun anInterruptedSessionSaysSoInTheHeadline() {
        val report = SessionReport.from(sidecar(interrupted = "interrupted by a device reboot"))
        assertTrue(report.headline.startsWith("INTERRUPTED"))
        assertEquals("interrupted by a device reboot", report.interruptedReason)
    }

    @Test
    fun durationsAreLegibleAtEveryScale() {
        assertEquals("9s", SessionReport.formatDuration(9_400))
        assertEquals("2m 30s", SessionReport.formatDuration(150_000))
        assertEquals("1h 30m", SessionReport.formatDuration(5_400_000))
    }
}

class RoundToTest {

    @Test
    fun fixedDecimalsWithoutStringFormat() {
        // Kotlin/Native has no String.format, and a raw Double renders as 0.11599999999999999.
        assertEquals("0.12", 0.116.roundTo(2))
        assertEquals("11.6", 11.6.roundTo(1))
        assertEquals("12", 11.6.roundTo(0))
        assertEquals("-2.50", (-2.5).roundTo(2))
        assertEquals("0.0", 0.0.roundTo(1))
        assertEquals("1.00", 0.999.roundTo(2))
    }

    @Test
    fun degenerateDoublesDoNotProduceGarbage() {
        assertEquals("n/a", Double.NaN.roundTo(2))
        assertEquals("∞", Double.POSITIVE_INFINITY.roundTo(2))
    }
}
