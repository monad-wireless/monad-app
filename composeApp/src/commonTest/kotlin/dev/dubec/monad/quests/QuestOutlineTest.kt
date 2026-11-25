package dev.dubec.monad.quests

import dev.dubec.monad.quests.data.dto.TaskType
import dev.dubec.monad.quests.presentation.quest_detail.QuestOutline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestOutlineTest {

    @Test
    fun aLongRouteReadsAsTwoActions() {
        val types = listOf(TaskType.START) +
            List(19) { listOf(TaskType.WALK_TO, TaskType.PROBE) }.flatten() +
            TaskType.FINISH
        val outline = QuestOutline.of(types)
        assertEquals(
            listOf("Walk to a marked place", "Scan the code and stand still for a short time"),
            outline.actions,
        )
        assertTrue(outline.purpose!!.contains("marked place"))
    }

    @Test
    fun aCountingQuestSaysThatTheCountIsTheReference() {
        val outline = QuestOutline.of(listOf(TaskType.START, TaskType.OBSERVE, TaskType.FINISH))
        assertEquals(listOf("Count the people you see"), outline.actions)
        assertTrue(outline.purpose!!.contains("reference"))
    }

    @Test
    fun anUnknownStepClaimsNothing() {
        val outline = QuestOutline.of(listOf(TaskType.START, TaskType.INFO, TaskType.FINISH))
        assertTrue(outline.actions.isEmpty())
        assertNull(outline.purpose)
    }
}
