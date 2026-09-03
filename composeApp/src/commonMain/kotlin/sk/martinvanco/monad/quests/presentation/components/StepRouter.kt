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
 * @param onComplete Callback when the step is completed, carrying what the step OBSERVED as JSON,
 *   or null when it observed nothing. Only a [TaskType.PROBE] reports anything today — the
 *   surveyed point it consumed — and that payload is the only record of where a dwell happened
 *   that leaves the device. Every other step passes null, explicitly rather than by omission, so
 *   adding an observation to one of them is a change at that step and nowhere else.
 * @param preScannedValue a code the participant already scanned outside the quest — the QR deep
 *   link that opened the app. Only a [TaskType.PROBE] consumes it, and only when it matches one of
 *   that step's targets, so it can never satisfy a step the participant did not stand in front of.
 */
@Composable
fun StepRouter(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: (stepData: String?) -> Unit,
    modifier: Modifier = Modifier,
    preScannedValue: String? = null,
) {
    when (task.type) {
        TaskType.QR_CODE, TaskType.SCAN_QR -> {
            QrCodeStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = { onComplete(null) },
                modifier = modifier
            )
        }

        TaskType.FIND_BLE_DEVICE -> {
            BleDeviceStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = { onComplete(null) },
                modifier = modifier
            )
        }

        TaskType.WAIT -> {
            WaitStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = { onComplete(null) },
                modifier = modifier
            )
        }

        TaskType.TEXT_BOX, TaskType.START, TaskType.FINISH -> {
            TextBoxStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = { onComplete(null) },
                modifier = modifier
            )
        }

        TaskType.SENSOR_CAPTURE -> {
            SensorCaptureStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = { onComplete(null) },
                modifier = modifier
            )
        }

        TaskType.BLE_ADVERTISE -> {
            BleAdvertiseStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = { onComplete(null) },
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

        TaskType.OBSERVE -> {
            ObserveStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = { onComplete(null) },
                modifier = modifier
            )
        }

        TaskType.CONNECT_TO_AP -> {
            ConnectToApStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = { onComplete(null) },
                modifier = modifier
            )
        }

        TaskType.WALK_TO, TaskType.INFO -> {
            TextBoxStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = { onComplete(null) },
                modifier = modifier
            )
        }
    }
}
