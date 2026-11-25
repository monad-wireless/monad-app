package dev.dubec.monad.quests

import dev.dubec.monad.quests.data.dto.ActiveTaskDto
import dev.dubec.monad.quests.data.dto.ProbeTarget
import dev.dubec.monad.quests.data.dto.TaskConfigParser
import dev.dubec.monad.quests.data.dto.TaskStatus
import dev.dubec.monad.quests.data.dto.TaskType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The two optional keys an instruction step reads, and the words for a surveyed room. */
class InstructionStepConfigTest {

    private fun task(config: String?) = ActiveTaskDto(
        name = "Study",
        description = "",
        type = TaskType.WALK_TO,
        status = TaskStatus.ACTIVE,
        config = config?.let { Json.parseToJsonElement(it) },
    )

    @Test
    fun studySessionStepHasItsOwnButtonAndCountsUp() {
        val t = task("""{"location":"Anywhere in the library","button":"I am leaving","elapsed":true}""")
        assertEquals("I am leaving", TaskConfigParser.buttonLabel(t))
        assertTrue(TaskConfigParser.showsElapsed(t))
    }

    @Test
    fun everyOtherStepKeepsContinueAndShowsNoClock() {
        for (config in listOf(null, "{}", """{"button":"  ","elapsed":false}""", """{"button":5}""")) {
            assertEquals("Continue", TaskConfigParser.buttonLabel(task(config)), "$config")
            assertFalse(TaskConfigParser.showsElapsed(task(config)), "$config")
        }
    }

    @Test
    fun elapsedTimeReadsAsAClock() {
        assertEquals("00:00", TaskConfigParser.formatElapsed(-5))
        assertEquals("07:05", TaskConfigParser.formatElapsed(425))
        assertEquals("1:07:05", TaskConfigParser.formatElapsed(4025))
    }

    @Test
    fun aRoomKeyNeverReachesAParticipant() {
        assertEquals("silent reading room", ProbeTarget(value = "x", room = "library-silent").roomLabel)
        assertEquals("library backside", ProbeTarget(value = "x", room = "library-backside").roomLabel)
    }
}
