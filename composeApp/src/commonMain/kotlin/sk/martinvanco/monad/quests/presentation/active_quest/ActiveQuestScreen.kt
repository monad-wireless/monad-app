package sk.martinvanco.monad.quests.presentation.active_quest

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import sk.martinvanco.monad.quests.domain.ActiveQuestDto
import sk.martinvanco.monad.quests.domain.ActiveTaskDto
import sk.martinvanco.monad.quests.domain.TaskStatus
import sk.martinvanco.monad.quests.domain.TaskType

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
    // Sample active quest data
    val sampleQuest = remember {
        ActiveQuestDto(
            id = questId,
            name = "Task Started",
            description = "Complete all steps to finish the quest",
            tasks = listOf(
                ActiveTaskDto(
                    name = "Scan QR code XY",
                    instruction = "Scan QR code XY located at the entrance in the room",
                    type = TaskType.SCAN_QR,
                    status = TaskStatus.ACTIVE
                ),
                ActiveTaskDto(
                    name = "Connect to Access Point",
                    instruction = "We need your device to be connected using WiFi",
                    type = TaskType.CONNECT_AT,
                    status = TaskStatus.SCHEDULED
                ),
                ActiveTaskDto(
                    name = "Use XYZ Equipment",
                    instruction = "We need your device to be connected using Bluetooth",
                    type = TaskType.WAIT,
                    status = TaskStatus.SCHEDULED
                ),
                ActiveTaskDto(
                    name = "Take a Photo",
                    instruction = "Take a photo of the completed setup",
                    type = TaskType.SUBMIT,
                    status = TaskStatus.SCHEDULED
                ),
                ActiveTaskDto(
                    name = "Submit Results",
                    instruction = "Submit your results and complete the quest",
                    type = TaskType.SUBMIT,
                    status = TaskStatus.SCHEDULED
                )
            ),
            points = 31.0f
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
                TaskCard(
                    taskNumber = index + 1,
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
                        // Handle report issue
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

@Composable
private fun TaskCard(
    taskNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    onReportIssue: () -> Unit
) {
    // Animated colors based on status
    val backgroundColor by animateColorAsState(
        targetValue = when (task.status) {
            TaskStatus.COMPLETED -> Color(0xFFF5F5F4) // stone-100
            TaskStatus.ACTIVE -> Color(0xFFF1F5F9) // slate-100
            TaskStatus.SCHEDULED -> Color(0xFFF1F5F9) // slate-100
        },
        animationSpec = tween(durationMillis = 300)
    )

    val numberBoxColor by animateColorAsState(
        targetValue = when (task.status) {
            TaskStatus.COMPLETED -> Color(0xFFEAF3EB) // light green
            TaskStatus.ACTIVE -> Color(0xFFE2E8FD) // light indigo
            TaskStatus.SCHEDULED -> Color(0xFFF2F2F2) // light gray
        },
        animationSpec = tween(durationMillis = 300)
    )

    val numberTextColor by animateColorAsState(
        targetValue = when (task.status) {
            TaskStatus.COMPLETED -> Color(0xFF4ADE80) // green-400
            TaskStatus.ACTIVE -> Color(0xFF5B6ECC) // indigo-500
            TaskStatus.SCHEDULED -> Color(0xFF71717A) // zinc-500
        },
        animationSpec = tween(durationMillis = 300)
    )

    val titleColor by animateColorAsState(
        targetValue = when (task.status) {
            TaskStatus.COMPLETED -> Color(0xFF22C55E) // green-500
            TaskStatus.ACTIVE -> Color(0xFF0F172A) // slate-900
            TaskStatus.SCHEDULED -> Color(0xFFA1A1AA) // zinc-400
        },
        animationSpec = tween(durationMillis = 300)
    )

    val descriptionColor by animateColorAsState(
        targetValue = when (task.status) {
            TaskStatus.COMPLETED -> Color(0xFF22C55E) // green-500
            TaskStatus.ACTIVE -> Color(0xFF0F172A) // slate-900
            TaskStatus.SCHEDULED -> Color(0xFFA1A1AA) // zinc-400
        },
        animationSpec = tween(durationMillis = 300)
    )

    val opacity = if (task.status == TaskStatus.COMPLETED) 0.6f else 1f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring<IntSize>(
                        dampingRatio = 0.8f,
                        stiffness = 300f
                    )
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Task header and description
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Step number box
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(numberBoxColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$taskNumber",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = numberTextColor.copy(alpha = opacity)
                        )
                    }

                    Text(
                        text = task.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor.copy(alpha = opacity)
                    )
                }

                Text(
                    text = task.instruction,
                    fontSize = 14.sp,
                    color = descriptionColor.copy(alpha = opacity)
                )
            }

            // Widget area and buttons (only for ACTIVE status)
            if (task.status == TaskStatus.ACTIVE) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Placeholder for dynamic widget
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE2E8F0)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Widget Placeholder\n(QR Scanner, Image, etc.)",
                            fontSize = 14.sp,
                            color = Color(0xFF64748B)
                        )
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Help/Issue button
                        TextButton(onClick = onReportIssue) {
                            Text(
                                text = "Report an issue",
                                fontSize = 14.sp,
                                color = Color.Black,
                                textDecoration = TextDecoration.Underline
                            )
                        }

                        // Primary action button
                        Button(
                            onClick = onComplete,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF5B6ECC)
                            ),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = getActionButtonText(task.type),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getActionButtonText(taskType: TaskType): String {
    return when (taskType) {
        TaskType.SCAN_QR -> "Scan"
        TaskType.CONNECT_AT -> "Connect"
        TaskType.SUBMIT -> "Submit"
        TaskType.START -> "Start"
        TaskType.STOP -> "Stop"
        TaskType.WAIT -> "Continue"
    }
}
