package sk.martinvanco.monad.quests.presentation.components.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import sk.martinvanco.monad.lab.domain.LabSensorModule
import sk.martinvanco.monad.lab.domain.SensorRequest
import sk.martinvanco.monad.lab.domain.labSensorModules
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.presentation.components.QuestStepCard

/**
 * Runs an optional sensor module — a LiDAR room scan, a UWB ranging burst — and reports what
 * actually happened.
 *
 * Two rules shape this screen, both learned the hard way in this codebase:
 *
 *  * **A capture that produced nothing is not a completed step.** A scan with no geometry or a
 *    ranging burst that heard no anchor leaves the step failed with the module's reason on screen,
 *    because a session that looks complete and is missing the measurement is worse than one that
 *    visibly failed.
 *  * **The module's own probe is the authority.** The backend already withheld quests whose
 *    capability this device lacks, but permissions get revoked and radios get switched off between
 *    the catalogue fetch and the run — so availability is re-checked here, at the moment of use.
 */
@Composable
fun SensorCaptureStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val config = task.config as? JsonObject
    val moduleId = config?.get("module")?.jsonPrimitive?.let {
        runCatching { it.content }.getOrNull()
    }
    val module = remember(moduleId) {
        labSensorModules().firstOrNull { it.id == moduleId }
    }

    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (module == null) {
                    Text(
                        text = "This step needs the '${moduleId ?: "unknown"}' sensor, which this " +
                            "build does not provide.",
                        fontSize = 14.sp,
                        color = Color(0xFFB91C1C),
                    )
                }
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
                }
                message?.let {
                    Text(
                        text = it,
                        fontSize = 14.sp,
                        color = if (failed) Color(0xFFB91C1C) else Color(0xFF15803D),
                    )
                }
            }
        },
        actions = {
            Button(
                onClick = {
                    val target = module ?: return@Button
                    running = true
                    failed = false
                    message = null
                    scope.launch {
                        when (val availability = target.probe()) {
                            is LabSensorModule.Availability.Available -> {
                                val outcome = target.capture(
                                    SensorRequest(sessionId = "", config = config)
                                )
                                running = false
                                outcome
                                    .onSuccess { capture ->
                                        failed = false
                                        message = capture.summary.entries
                                            .joinToString(", ") { "${it.key}=${it.value}" }
                                            .ifBlank { "capture complete" }
                                        onComplete()
                                    }
                                    .onFailure {
                                        failed = true
                                        message = it.message ?: "capture failed"
                                    }
                            }

                            is LabSensorModule.Availability.Unsupported -> {
                                running = false
                                failed = true
                                message = availability.reason
                            }

                            is LabSensorModule.Availability.NeedsPermission -> {
                                running = false
                                failed = true
                                message = "needs permission: ${availability.permission}"
                            }
                        }
                    }
                },
                enabled = module != null && !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Capturing…" else "Run capture")
            }
        }
    )
}
