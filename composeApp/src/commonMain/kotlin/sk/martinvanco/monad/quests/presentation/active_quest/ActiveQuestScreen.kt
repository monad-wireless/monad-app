package sk.martinvanco.monad.quests.presentation.active_quest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sk.martinvanco.monad.quests.domain.ActiveQuestDto
import sk.martinvanco.monad.quests.presentation.end_quest.EndQuestScreen
import sk.martinvanco.monad.quests.domain.ActiveTaskDto
import sk.martinvanco.monad.quests.domain.TaskStatus
import sk.martinvanco.monad.quests.domain.TaskType
import sk.martinvanco.monad.quests.presentation.components.StepRouter

data class ActiveQuestScreen(
    val questId: String
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        ActiveQuestScreenContent(
            questId = questId,
            onEndQuest = { questName ->
                navigator.push(EndQuestScreen(questId = questId, questName = questName))
            }
        )
    }
}

@Composable
private fun ActiveQuestScreenContent(
    questId: String,
    onEndQuest: (questName: String) -> Unit
) {
    // QR code value for simulation - same for all QR scans
    val qrCodeValue = "MONAD_QR"

    val sampleQuest = remember {
        ActiveQuestDto(
            id = questId,
            name = "Indoor Navigation Research",
            description = "Complete all steps to finish the quest",
            tasks = listOf(
                // Step 1: Find MONAD1
                ActiveTaskDto(
                    name = "Find BLE Beacon MONAD1",
                    description = "Locate the first BLE beacon.",
                    type = TaskType.FIND_BLE_DEVICE,
                    status = TaskStatus.ACTIVE,
                    config = buildJsonObject {
                        put("device_name", "MONAD1")
                    }
                ),
                // Step 2: Find MONAD2
                ActiveTaskDto(
                    name = "Find BLE Beacon MONAD2",
                    description = "Locate the second BLE beacon.",
                    type = TaskType.FIND_BLE_DEVICE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("device_name", "MONAD2")
                    }
                ),
                // Step 3: Find MONAD3
                ActiveTaskDto(
                    name = "Find BLE Beacon MONAD3",
                    description = "Locate the third BLE beacon.",
                    type = TaskType.FIND_BLE_DEVICE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("device_name", "MONAD3")
                    }
                ),
                // Step 4: Find MONAD4
                ActiveTaskDto(
                    name = "Find BLE Beacon MONAD4",
                    description = "Locate the fourth BLE beacon.",
                    type = TaskType.FIND_BLE_DEVICE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("device_name", "MONAD4")
                    }
                ),
                // Step 5: Find MONAD5
                ActiveTaskDto(
                    name = "Find BLE Beacon MONAD5",
                    description = "Locate the fifth BLE beacon.",
                    type = TaskType.FIND_BLE_DEVICE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("device_name", "MONAD5")
                    }
                ),
                // Step 6: Find MONAD6
                ActiveTaskDto(
                    name = "Find BLE Beacon MONAD6",
                    description = "Locate the sixth BLE beacon.",
                    type = TaskType.FIND_BLE_DEVICE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("device_name", "MONAD6")
                    }
                )

                /* QR Code steps - commented out for now
                ActiveTaskDto(
                    name = "Scan QR Code",
                    description = "Scan the QR code at this location.",
                    type = TaskType.QR_CODE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("expected_value", qrCodeValue)
                        put("location", "Checkpoint 1")
                    }
                ),
                ActiveTaskDto(
                    name = "Scan QR Code",
                    description = "Scan the QR code at this location.",
                    type = TaskType.QR_CODE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("expected_value", qrCodeValue)
                        put("location", "Checkpoint 2")
                    }
                ),
                ActiveTaskDto(
                    name = "Scan QR Code",
                    description = "Scan the QR code at this location.",
                    type = TaskType.QR_CODE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("expected_value", qrCodeValue)
                        put("location", "Checkpoint 3")
                    }
                ),
                ActiveTaskDto(
                    name = "Scan QR Code",
                    description = "Scan the QR code at this location.",
                    type = TaskType.QR_CODE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("expected_value", qrCodeValue)
                        put("location", "Checkpoint 4")
                    }
                ),
                ActiveTaskDto(
                    name = "Scan QR Code",
                    description = "Scan the QR code at this location.",
                    type = TaskType.QR_CODE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("expected_value", qrCodeValue)
                        put("location", "Checkpoint 5")
                    }
                )
                */
            ),
            points = 50.0f
        )
    }

    var tasks by remember { mutableStateOf(sampleQuest.tasks) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        ActiveQuestTopBar(title = sampleQuest.name)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            tasks.forEachIndexed { index, task ->
                StepRouter(
                    stepNumber = index + 1,
                    task = task,
                    onComplete = {
                        tasks = tasks.mapIndexed { idx, t ->
                            when {
                                idx == index -> t.copy(status = TaskStatus.COMPLETED)
                                idx == index + 1 -> t.copy(status = TaskStatus.ACTIVE)
                                else -> t
                            }
                        }
                    },
                    onReportIssue = {
                        // TODO: Handle report issue
                    }
                )
            }
        }

        // End Task button at bottom - secondary style
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            TextButton(onClick = { onEndQuest(sampleQuest.name) }) {
                Text(
                    text = "End Quest",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                    textDecoration = TextDecoration.Underline
                )
            }
        }
    }
}

@Composable
private fun ActiveQuestTopBar(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFFFFF))
            .displayCutoutPadding()
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .shadow(
                    elevation = 1.dp,
                    shape = RoundedCornerShape(0.dp),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.05f),
                    spotColor = Color.Black.copy(alpha = 0.05f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Green dot indicator
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF22C55E))
                )

                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black
                )
            }
        }
    }
}

