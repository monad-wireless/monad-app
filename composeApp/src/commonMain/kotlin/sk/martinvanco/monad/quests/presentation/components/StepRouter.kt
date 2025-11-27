package sk.martinvanco.monad.quests.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import sk.martinvanco.monad.quests.domain.ActiveTaskDto
import sk.martinvanco.monad.quests.domain.TaskType
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
 * @param onReportIssue Callback when user reports a problem
 */
@Composable
fun StepRouter(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    onReportIssue: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (task.type) {
        TaskType.QR_CODE -> {
            QrCodeStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                onReportIssue = onReportIssue,
                modifier = modifier
            )
        }

        TaskType.FIND_BLE_DEVICE -> {
            BleDeviceStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                onReportIssue = onReportIssue,
                modifier = modifier
            )
        }

        TaskType.WAIT -> {
            WaitStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                onReportIssue = onReportIssue,
                modifier = modifier
            )
        }

        TaskType.TEXT_BOX -> {
            TextBoxStep(
                stepNumber = stepNumber,
                task = task,
                onComplete = onComplete,
                onReportIssue = onReportIssue,
                modifier = modifier
            )
        }
    }
}
