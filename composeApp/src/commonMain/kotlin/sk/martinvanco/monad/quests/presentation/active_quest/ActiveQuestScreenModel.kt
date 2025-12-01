package sk.martinvanco.monad.quests.presentation.active_quest

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sk.martinvanco.monad.ble.domain.BleSensingService
import sk.martinvanco.monad.quests.domain.ActiveTaskDto
import sk.martinvanco.monad.quests.domain.TaskStatus
import sk.martinvanco.monad.quests.domain.TaskType

class ActiveQuestScreenModel(
    private val bleSensingService: BleSensingService,
    private val questId: String
) : StateScreenModel<ActiveQuestState>(ActiveQuestState(questId = questId)) {

    init {
        initializeQuest()
        startBleSensing()
        observeBleRecordCount()
    }

    private fun initializeQuest() {
        // Sample quest data for POC
        val sampleTasks = listOf(
            ActiveTaskDto(
                name = "Find BLE Beacon MONAD1",
                description = "Locate the first BLE beacon.",
                type = TaskType.FIND_BLE_DEVICE,
                status = TaskStatus.ACTIVE,
                config = buildJsonObject { put("device_name", "MONAD1") }
            ),
            ActiveTaskDto(
                name = "Find BLE Beacon MONAD2",
                description = "Locate the second BLE beacon.",
                type = TaskType.FIND_BLE_DEVICE,
                status = TaskStatus.SCHEDULED,
                config = buildJsonObject { put("device_name", "MONAD2") }
            ),
            ActiveTaskDto(
                name = "Find BLE Beacon MONAD3",
                description = "Locate the third BLE beacon.",
                type = TaskType.FIND_BLE_DEVICE,
                status = TaskStatus.SCHEDULED,
                config = buildJsonObject { put("device_name", "MONAD3") }
            ),
            ActiveTaskDto(
                name = "Find BLE Beacon MONAD4",
                description = "Locate the fourth BLE beacon.",
                type = TaskType.FIND_BLE_DEVICE,
                status = TaskStatus.SCHEDULED,
                config = buildJsonObject { put("device_name", "MONAD4") }
            ),
            ActiveTaskDto(
                name = "Find BLE Beacon MONAD5",
                description = "Locate the fifth BLE beacon.",
                type = TaskType.FIND_BLE_DEVICE,
                status = TaskStatus.SCHEDULED,
                config = buildJsonObject { put("device_name", "MONAD5") }
            ),
            ActiveTaskDto(
                name = "Find BLE Beacon MONAD6",
                description = "Locate the sixth BLE beacon.",
                type = TaskType.FIND_BLE_DEVICE,
                status = TaskStatus.SCHEDULED,
                config = buildJsonObject { put("device_name", "MONAD6") }
            )
        )

        mutableState.value = mutableState.value.copy(
            questName = "Indoor Navigation Research",
            tasks = sampleTasks,
            points = 50f
        )
    }

    private fun startBleSensing() {
        screenModelScope.launch {
            val result = bleSensingService.startSensing(questId)
            if (result.isSuccess) {
                mutableState.value = mutableState.value.copy(isBleCollecting = true)
            }
        }
    }

    private fun observeBleRecordCount() {
        bleSensingService.recordCount
            .onEach { count ->
                mutableState.value = mutableState.value.copy(bleRecordCount = count)
            }
            .launchIn(screenModelScope)

        bleSensingService.isCollecting
            .onEach { isCollecting ->
                mutableState.value = mutableState.value.copy(isBleCollecting = isCollecting)
            }
            .launchIn(screenModelScope)
    }

    fun onEvent(event: ActiveQuestEvent) {
        when (event) {
            is ActiveQuestEvent.CompleteTask -> completeTask(event.taskIndex)
            is ActiveQuestEvent.ReportIssue -> { /* TODO */ }
            is ActiveQuestEvent.EndQuest -> stopBleSensing()
        }
    }

    private fun completeTask(taskIndex: Int) {
        val tasks = mutableState.value.tasks.mapIndexed { idx, task ->
            when {
                idx == taskIndex -> task.copy(status = TaskStatus.COMPLETED)
                idx == taskIndex + 1 -> task.copy(status = TaskStatus.ACTIVE)
                else -> task
            }
        }
        mutableState.value = mutableState.value.copy(tasks = tasks)
    }

    private fun stopBleSensing() {
        bleSensingService.stopSensing()
    }

    override fun onDispose() {
        super.onDispose()
        stopBleSensing()
    }
}
