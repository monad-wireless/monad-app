package dev.dubec.monad.quests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.dubec.monad.quests.domain.port.QuestStepRecord
import dev.dubec.monad.quests.domain.stepWindowMillis

/**
 * A resumed run could never complete (monad-app#2).
 *
 * The first step is inserted with no start. The completion used the active-quest screen's load
 * time as its start, and after a restart that time was later than the step's completion, so the
 * backend refused the whole run with 400 `steps[0].started_at`. The backend requires
 * `started_at < completed_at` for every step; these tests pin that for the restart case.
 */
class StepWindowTest {

    private fun record(
        status: String,
        startedAt: Long? = null,
        completedAt: Long? = null,
        createdAt: Long = QUEST_STARTED,
    ) = QuestStepRecord(
        stepCompletionId = "step",
        status = status,
        startedAtMillis = startedAt,
        completedAtMillis = completedAt,
        createdAtMillis = createdAt,
        stepDataJson = null,
        skipMessage = null,
        skipErrorCode = null,
    )

    @Test
    fun firstStepCompletedBeforeARestartStartsAtItsCreationTime() {
        // Briefing completed 20 s into the run, the app restarted at 36 s, the run ended at 44 s.
        val briefing = record("completed", startedAt = null, completedAt = QUEST_STARTED + 20_000)
        val (start, end) = stepWindowMillis(briefing, runEndedMillis = QUEST_STARTED + 44_000)
        assertEquals(QUEST_STARTED, start)
        assertEquals(QUEST_STARTED + 20_000, end)
        assertTrue(start < end)
    }

    @Test
    fun recordedTimesAreKept() {
        val step = record("completed", startedAt = QUEST_STARTED + 20_000, completedAt = QUEST_STARTED + 30_000)
        assertEquals(QUEST_STARTED + 20_000 to QUEST_STARTED + 30_000, stepWindowMillis(step, QUEST_STARTED + 44_000))
    }

    @Test
    fun aStepNeverReachedSpansFromQuestStartToRunEnd() {
        val pending = record("pending")
        assertEquals(QUEST_STARTED to QUEST_STARTED + 44_000, stepWindowMillis(pending, QUEST_STARTED + 44_000))
    }

    private companion object {
        const val QUEST_STARTED = 1_795_433_009_000L
    }
}
