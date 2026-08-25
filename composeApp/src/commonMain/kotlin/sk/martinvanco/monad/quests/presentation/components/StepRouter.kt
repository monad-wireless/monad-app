package sk.martinvanco.monad.quests.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.data.dto.TaskType
import sk.martinvanco.monad.quests.presentation.components.steps.*

/**
 * Router component that renders the appropriate step component based on task type
 *
 * This acts as a factory that dynamically selects and renders the correct
 * step implementation based on the TaskType enum value.
 *
 * @param stepNumber Sequential number of this step in the quest
 * @param task The active task to render
 * @param onComplete Callback when step is completed
 * @param preScannedValue a code the participant already scanned outside the quest — the QR deep
 *   link that opened the app. Only a [TaskType.PROBE] consumes it, and only when it matches one of
 *   that step's targets, so it can never satisfy a step the participant did not stand in front of.
 */
@Composable
fun StepRouter(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    preScannedValue: String? = null,
) {
    when (task.type) {
        TaskType.QR_CODE, TaskType.SCAN_QR -> {
            QrCodeStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                modifier = modifier
            )
        }

        TaskType.FIND_BLE_DEVICE -> {
            BleDeviceStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                modifier = modifier
            )
        }

        TaskType.WAIT -> {
            WaitStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                modifier = modifier
            )
        }

        TaskType.TEXT_BOX, TaskType.START, TaskType.FINISH -> {
            TextBoxStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                modifier = modifier
            )
        }

        TaskType.SENSOR_CAPTURE -> {
            SensorCaptureStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                modifier = modifier
            )
        }

        TaskType.BLE_ADVERTISE -> {
            BleAdvertiseStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                modifier = modifier
            )
        }

        TaskType.PROBE -> {
            ProbeStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                modifier = modifier,
                preScannedValue = preScannedValue,
            )
        }

        TaskType.CONNECT_TO_AP -> {
            ConnectToApStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                modifier = modifier
            )
        }

        TaskType.WALK_TO, TaskType.INFO -> {
            TextBoxStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                modifier = modifier
            )
        }
    }
}
