package dev.dubec.monad.quests.presentation.quest_detail

import dev.dubec.monad.quests.data.dto.TaskType

/**
 * What a quest asks of a participant, and what the study gets from it, in words.
 *
 * Read from the step types. A list of forty step names is not an answer to "what will I do?", and
 * the types hold it without any text of their own on the server.
 */
data class QuestOutline(
    /** What the participant does, in the order it first happens, each thing once. */
    val actions: List<String>,
    /** Why it helps, or null when no step of this quest has a reason this build can name. */
    val purpose: String?,
) {
    companion object {
        fun of(types: List<TaskType>): QuestOutline = QuestOutline(
            actions = types.mapNotNull(::actionFor).distinct(),
            purpose = when {
                TaskType.OBSERVE in types ->
                    "Your count is the reference. We compare the estimate from the sensors with it."

                TaskType.PROBE in types ->
                    "We measured the position of every marked place. Your visit shows how well the " +
                        "sensors can tell where a phone is."

                else -> null
            },
        )

        private fun actionFor(type: TaskType): String? = when (type) {
            TaskType.WALK_TO -> "Walk to a marked place"
            TaskType.QR_CODE, TaskType.SCAN_QR -> "Scan a printed code"
            TaskType.PROBE -> "Scan the code and stand still for a short time"
            TaskType.OBSERVE -> "Count the people you see"
            TaskType.WAIT -> "Wait until the timer ends"
            TaskType.FIND_BLE_DEVICE -> "Find a Bluetooth device"
            TaskType.SENSOR_CAPTURE -> "Record a measurement with your phone"
            TaskType.BLE_ADVERTISE -> "Let your phone send a Bluetooth signal"
            TaskType.CONNECT_TO_AP -> "Connect to the study network"
            TaskType.LIVE -> "Follow the instructions on the screen"
            TaskType.START, TaskType.FINISH, TaskType.TEXT_BOX, TaskType.INFO -> null
        }
    }
}
