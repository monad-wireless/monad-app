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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sk.martinvanco.monad.quests.domain.ActiveQuestDto
import sk.martinvanco.monad.quests.domain.ActiveTaskDto
import sk.martinvanco.monad.quests.domain.TaskStatus
import sk.martinvanco.monad.quests.domain.TaskType
import sk.martinvanco.monad.quests.presentation.components.StepRouter

data class ActiveQuestScreen(
    val questId: String
) : Screen {
    @Composable
    override fun Content() {
        ActiveQuestScreenContent(questId = questId)
    }
}

@Composable
private fun ActiveQuestScreenContent(questId: String) {
    // Sample active quest data - comprehensive demo of all step types
    val sampleQuest = remember {
        ActiveQuestDto(
            id = questId,
            name = "Indoor Navigation Research",
            description = "Complete all steps to finish the quest",
            tasks = listOf(
                // Step 1: TEXT_BOX - Welcome instructions
                ActiveTaskDto(
                    name = "Welcome to the Experiment",
                    description = "# Welcome!\n\nThank you for participating in our indoor navigation research study.\n\n## What You'll Do\n\nDuring this quest, you will:\n\n1. Read information and instructions\n2. Scan QR codes at specific locations\n3. Find BLE beacons using your device\n4. Wait at designated checkpoints\n\n## Important Guidelines\n\n- Keep Bluetooth enabled throughout the experiment\n- Follow the instructions carefully\n- Do not close the app during tasks\n- Report any issues using the \"Report an issue\" button\n\n## Safety\n\n- Watch your step while walking\n- Be aware of your surroundings\n- Stop if you feel uncomfortable\n\nTap \"Continue\" when you're ready to begin.",
                    type = TaskType.TEXT_BOX,
                    status = TaskStatus.ACTIVE,
                    config = null
                ),

                // Step 2: QR_CODE - Entrance checkpoint
                ActiveTaskDto(
                    name = "Scan QR Code at Entrance",
                    description = "Locate and scan the QR code at the main entrance to verify your starting position",
                    type = TaskType.QR_CODE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("expected_value", "MONAD_QR")
                        put("location", "Main entrance, next to the door handle")
                    }
                ),

                // Step 3: FIND_BLE_DEVICE - First beacon
                ActiveTaskDto(
                    name = "Find BLE Beacon in Corridor A",
                    description = "Walk down Corridor A until your device detects the BLE beacon. The app will show you how close you are.",
                    type = TaskType.FIND_BLE_DEVICE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("device_name", "MONAD")
                        /*put("device_id", "A4:C1:38:F2:1D:8E")*/
                    }
                ),

                // Step 4: WAIT - Data collection at checkpoint 1
                ActiveTaskDto(
                    name = "Wait at Checkpoint 1",
                    description = "Stand still at this location for 30 seconds while we collect positioning data. Do not move or close the app.",
                    type = TaskType.WAIT,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("timeout_seconds", 30)
                    }
                ),

                // Step 5: QR_CODE - Lab A checkpoint
                ActiveTaskDto(
                    name = "Scan QR Code at Lab A",
                    description = "Navigate to Lab A and scan the QR code on the door",
                    type = TaskType.QR_CODE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("expected_value", "LAB_A_DOOR_2024")
                        put("location", "Lab A entrance, on the door frame")
                    }
                ),

                // Step 6: FIND_BLE_DEVICE - Second beacon
                ActiveTaskDto(
                    name = "Find BLE Beacon in Lab A",
                    description = "Enter Lab A and locate the BLE beacon inside. Walk around slowly until the signal is detected.",
                    type = TaskType.FIND_BLE_DEVICE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("device_name", "Monad_Beacon_LabA")
                        put("device_id", "B8:27:EB:A3:9C:F1")
                    }
                ),

                // Step 7: WAIT - Data collection at checkpoint 2
                ActiveTaskDto(
                    name = "Wait at Checkpoint 2",
                    description = "Remain stationary for 45 seconds while we perform detailed signal measurements",
                    type = TaskType.WAIT,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("timeout_seconds", 45)
                    }
                ),

                // Step 8: QR_CODE - Return checkpoint
                ActiveTaskDto(
                    name = "Scan Return Checkpoint QR",
                    description = "Walk back to the main corridor and scan the return checkpoint QR code",
                    type = TaskType.QR_CODE,
                    status = TaskStatus.SCHEDULED,
                    config = buildJsonObject {
                        put("expected_value", "RETURN_CHECKPOINT_2024")
                        put("location", "Main corridor, near the water fountain")
                    }
                ),

                // Step 9: TEXT_BOX - Completion message
                ActiveTaskDto(
                    name = "Quest Complete!",
                    description = "# Congratulations!\n\nYou have successfully completed the Indoor Navigation Research quest.\n\n## What's Next\n\n- Your data has been collected and will help improve indoor positioning systems\n- You've earned 50 points for your participation\n- The quest will automatically submit when you tap Continue\n\n## Thank You!\n\nYour contribution is valuable to our research. If you experienced any issues during the quest, please use the \"Report an issue\" button.\n\nOtherwise, tap \"Continue\" to submit your results.",
                    type = TaskType.TEXT_BOX,
                    status = TaskStatus.SCHEDULED,
                    config = null
                )
            ),
            points = 50.0f
        )
    }

    var tasks by remember { mutableStateOf(sampleQuest.tasks) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Custom top bar with green dot
        ActiveQuestTopBar(title = sampleQuest.name)

        // Scrollable content
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
            TextButton(onClick = { /* TODO: Handle end task */ }) {
                Text(
                    text = "End Task",
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

