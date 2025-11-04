package sk.martinvanco.monad.quests.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import sk.martinvanco.monad.core.components.ScreenWithBackNavigation
import sk.martinvanco.monad.quests.domain.QuestDetailDto
import sk.martinvanco.monad.quests.domain.TaskDto
import sk.martinvanco.monad.quests.domain.TaskType

data class QuestDetailScreen(val questId: String) : Screen {

    // Sample quest data
    private val sampleQuest = QuestDetailDto(
        id = "test-quest-id-123",
        name = "Network fingerprinting task to better track positioning",
        description = "Initial state description, where the user will start, how should it start, make sure to have clean sight, test details, Lorem ipsum dolrem sit amet and more is here to say...",
        duration = 15,
        questType = "Scan & Walk",
        points = 31f,
        tasks = listOf(
            TaskDto(
                name = "Scan QR code XY",
                instruction = "Do this, wait for XY, ... Make sure no one is blocking the view, ....",
                type = TaskType.SCAN_QR
            ),TaskDto(
                name = "Scan QR code XY",
                instruction = "Do this, wait for XY, ... Make sure no one is blocking the view, ....",
                type = TaskType.SCAN_QR
            ),TaskDto(
                name = "Scan QR code XY",
                instruction = "Do this, wait for XY, ... Make sure no one is blocking the view, ....",
                type = TaskType.SCAN_QR
            ),
            TaskDto(
                name = "Walk to QR code XY & Scan it",
                instruction = "Do this, wait for XY, ... Make sure no one is blocking the view, ....",
                type = TaskType.SCAN_QR
            ),
            TaskDto(
                name = "Walk back to QR XY",
                instruction = "Do this, wait for XY, ... Make sure no one is blocking the view, ....",
                type = TaskType.SCAN_QR
            )
        )
    )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val density = LocalDensity.current
        var tasksHeight by remember { mutableStateOf(0.dp) }

        ScreenWithBackNavigation(
            title = "Quest Detail",
            onBackClick = { navigator.pop() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp, 32.dp, 24.dp, 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Quest Type Badge
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F142F))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = "Quest Type",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = sampleQuest.questType,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color.White
                    )
                }

                // Quest Title
                Text(
                    text = sampleQuest.name,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F142F),
                    letterSpacing = (-0.8).sp,
                    lineHeight = 36.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = sampleQuest.description,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.Black,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Task List (${sampleQuest.tasks.size} tasks)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F142F)
                )

                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (tasksHeight > 0.dp) {
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(tasksHeight + 24.dp - 12.dp) // tasks height + spacing - center offset
                                        .offset(x = 12.dp, y = 12.dp) // Position in middle of step number
                                        .drawBehind {
                                            val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                            drawLine(
                                                color = Color(0xFF52525B),
                                                start = Offset(0f, 0f),
                                                end = Offset(0f, size.height),
                                                strokeWidth = 3f,
                                                pathEffect = pathEffect
                                            )
                                        }
                                )
                            }

                            // Tasks Column - measure actual height
                            Column(
                                modifier = Modifier
                                    .onGloballyPositioned { coordinates ->
                                        tasksHeight = with(density) { coordinates.size.height.toDp() }
                                    },
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                sampleQuest.tasks.forEachIndexed { index, task ->
                                    TaskItem(
                                        taskNumber = index + 1,
                                        task = task
                                    )
                                }
                            }
                        }

                        // Points Display - star aligned with step icons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Star icon - same size as step box
                            Icon(
                                imageVector = Icons.Filled.Stars,
                                contentDescription = "Points",
                                tint = Color(0xFFEAB308),
                                modifier = Modifier.size(24.dp)
                            )

                            Text(
                                text = "+ ${sampleQuest.points.toInt()} points",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFEAB308)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Start Button
                Button(
                    onClick = { /* TODO: Start quest */ },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5B6ECC)
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Start the experiment",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }

    @Composable
    private fun TaskItem(
        taskNumber: Int,
        task: TaskDto
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Task Number - Indigo background square
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE0E7FF)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$taskNumber",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF5B6ECC)
                )
            }

            // Task Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = task.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF0F142F)
                )
                Text(
                    text = task.instruction,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.Black,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
