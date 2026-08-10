package sk.martinvanco.monad.quests.presentation.quest_detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform.getKoin
import sk.martinvanco.monad.core.presentation.components.ScreenWithBackNavigation
import sk.martinvanco.monad.quests.data.dto.QuestDetailDto
import sk.martinvanco.monad.quests.data.dto.TaskDto
import sk.martinvanco.monad.quests.presentation.active_quest.ActiveQuestScreen

data class QuestDetailScreen(val questId: String) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = remember {
            getKoin().get<QuestDetailScreenModel> { parametersOf(questId) }
        }
        val state by screenModel.state.collectAsState()
        val density = LocalDensity.current
        var tasksHeight by remember { mutableStateOf(0.dp) }

        // Navigate to ActiveQuestScreen when enrollment is created
        LaunchedEffect(state.enrollmentId) {
            if (state.enrollmentId != null) {
                navigator.replaceAll(ActiveQuestScreen(questId))
            }
        }

        // Error dialog for start quest failures
        if (state.startQuestError != null) {
            AlertDialog(
                onDismissRequest = { screenModel.onEvent(QuestDetailEvent.DismissStartQuestError) },
                title = {
                    Text(
                        text = "Cannot Start Quest",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Text(text = state.startQuestError ?: "An error occurred")
                },
                confirmButton = {
                    TextButton(
                        onClick = { screenModel.onEvent(QuestDetailEvent.DismissStartQuestError) }
                    ) {
                        Text(
                            text = "OK",
                            color = Color(0xFF5B6ECC),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            )
        }

        ScreenWithBackNavigation(
            title = "Quest Detail",
            onBackClick = { navigator.pop() }
        ) {
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF5B6ECC)
                        )
                    }
                }
                state.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.error ?: "Unknown error",
                                fontSize = 16.sp,
                                color = Color(0xFF666666),
                                textAlign = TextAlign.Center
                            )
                            TextButton(
                                onClick = { screenModel.onEvent(QuestDetailEvent.RetryLoad) }
                            ) {
                                Text(
                                    text = "Try Again",
                                    color = Color(0xFF5B6ECC),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                state.quest != null -> {
                    val quest = state.quest!!
                    QuestDetailContent(
                        quest = quest,
                        tasksHeight = tasksHeight,
                        onTasksHeightChange = { tasksHeight = it },
                        density = density,
                        isStartingQuest = state.isStartingQuest,
                        onStartClick = { screenModel.onEvent(QuestDetailEvent.StartQuest) }
                    )
                }
            }
        }
    }

    @Composable
    private fun QuestDetailContent(
        quest: QuestDetailDto,
        tasksHeight: androidx.compose.ui.unit.Dp,
        onTasksHeightChange: (androidx.compose.ui.unit.Dp) -> Unit,
        density: androidx.compose.ui.unit.Density,
        isStartingQuest: Boolean,
        onStartClick: () -> Unit
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Scrollable content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp, 32.dp, 24.dp, 80.dp), // Extra bottom padding for fixed button
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
                    text = quest.questType,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White
                )
            }

            // Quest Title
            Text(
                text = quest.name,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF0F142F),
                letterSpacing = (-0.8).sp,
                lineHeight = 36.sp
            )

            // Quest image - right after title, before description
            quest.imageUrl?.let { imageUrl ->
                Spacer(modifier = Modifier.height(12.dp))
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "Quest image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = quest.description,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                color = Color.Black,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Task List (${quest.tasks.size} tasks)",
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
                                    .height(tasksHeight + 24.dp - 12.dp)
                                    .offset(x = 12.dp, y = 12.dp)
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

                        Column(
                            modifier = Modifier
                                .onGloballyPositioned { coordinates ->
                                    onTasksHeightChange(with(density) { coordinates.size.height.toDp() })
                                },
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            quest.tasks.forEachIndexed { index, task ->
                                TaskItem(
                                    taskNumber = index + 1,
                                    task = task
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stars,
                            contentDescription = "Points",
                            tint = Color(0xFFEAB308),
                            modifier = Modifier.size(24.dp)
                        )

                        Text(
                            text = "+ ${quest.points.toInt()} points",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEAB308)
                        )
                    }
                }
            }
            }

            // Fixed button section at bottom with white background
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = onStartClick,
                    enabled = !isStartingQuest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5B6ECC),
                        disabledContainerColor = Color(0xFF5B6ECC).copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    if (isStartingQuest) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Starting...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    } else {
                        Text(
                            text = "Start Quest",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
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
                    text = task.description,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.Black,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
