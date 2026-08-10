package sk.martinvanco.monad.quests.presentation.active_quest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform.getKoin
import sk.martinvanco.monad.quests.presentation.components.StepRouter
import sk.martinvanco.monad.quests.presentation.quest_completed.QuestCompletedScreen
import sk.martinvanco.monad.quests.presentation.quest_ended.QuestEndedEarlyScreen

data class ActiveQuestScreen(
    val questId: String
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            getKoin().get<ActiveQuestScreenModel> { parametersOf(questId) }
        }
        val state by screenModel.state.collectAsState()

        // Navigate home when requested
        LaunchedEffect(state.shouldNavigateHome) {
            if (state.shouldNavigateHome) {
                navigator.popUntilRoot()
            }
        }

        // Navigate to ended early screen when quest is ended early
        LaunchedEffect(state.navigateToEndedEarlyScreen) {
            if (state.navigateToEndedEarlyScreen) {
                navigator.replace(
                    QuestEndedEarlyScreen(
                        questId = questId,
                        enrollmentId = state.enrollmentId,
                        userName = state.userName,
                        startTime = state.startTime
                    )
                )
            }
        }

        // Navigate to completed screen when quest is completed
        LaunchedEffect(state.navigateToCompletedScreen) {
            if (state.navigateToCompletedScreen) {
                navigator.replace(
                    QuestCompletedScreen(
                        questId = questId,
                        enrollmentId = state.enrollmentId,
                        userName = state.userName,
                        startTime = state.startTime,
                        uploadAlreadyCompleted = true
                    )
                )
            }
        }

        // Upload progress dialog
        if (state.isUploading) {
            Dialog(
                onDismissRequest = { },
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF22C55E),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Uploading Data",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0F142F)
                        )
                        Text(
                            text = state.uploadProgress,
                            fontSize = 14.sp,
                            color = Color(0xFF6B7280),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Error dialog
        if (state.completionError != null) {
            AlertDialog(
                onDismissRequest = { screenModel.onEvent(ActiveQuestEvent.DismissCompletionError) },
                title = {
                    Text(
                        text = "Upload Failed",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Text(text = state.completionError ?: "An error occurred")
                },
                confirmButton = {
                    Button(
                        onClick = { screenModel.onEvent(ActiveQuestEvent.RetryUpload) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC))
                    ) {
                        Text("Retry", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { screenModel.onEvent(ActiveQuestEvent.DismissCompletionError) }
                    ) {
                        Text("Cancel", color = Color(0xFF6B7280))
                    }
                }
            )
        }

        // End quest confirmation dialog
        if (state.showEndQuestConfirmation) {
            AlertDialog(
                onDismissRequest = { screenModel.onEvent(ActiveQuestEvent.DismissEndQuestConfirmation) },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(48.dp)
                    )
                },
                title = {
                    Text(
                        text = "End Quest?",
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to end the quest? Your progress will be uploaded but you won't receive full points.",
                        textAlign = TextAlign.Center
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { screenModel.onEvent(ActiveQuestEvent.ConfirmEndQuest) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Text("Yes, End Quest", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { screenModel.onEvent(ActiveQuestEvent.DismissEndQuestConfirmation) }
                    ) {
                        Text("Continue Quest", color = Color(0xFF5B6ECC))
                    }
                }
            )
        }

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            ActiveQuestTopBar(
                title = state.questName
            )
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF5B6ECC))
                    }
                }
                state.error != null -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = state.error ?: "Unknown error",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                TextButton(onClick = { navigator.pop() }) {
                                    Text("Go Back", color = Color(0xFF6B7280))
                                }
                                Button(
                                    onClick = { screenModel.onEvent(ActiveQuestEvent.RetryLoad) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC))
                                ) {
                                    Text("Retry", color = Color.White)
                                }
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Non-fatal instrument failure: the radio did not start, but the quest is
                        // still walkable. Shown as a banner above the steps rather than instead of
                        // them — replacing the list stranded the participant with no way forward.
                        state.instrumentWarning?.let { warning ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFEF3C7), RoundedCornerShape(8.dp))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = warning,
                                    color = Color(0xFF92400E),
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "You can still complete the steps — the radio " +
                                        "measurement will be missing from this run.",
                                    color = Color(0xFF92400E),
                                    fontSize = 13.sp
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TextButton(onClick = {
                                        screenModel.onEvent(ActiveQuestEvent.DismissInstrumentWarning)
                                    }) {
                                        Text("Dismiss", color = Color(0xFF92400E))
                                    }
                                    TextButton(onClick = {
                                        screenModel.onEvent(ActiveQuestEvent.RetryInstrument)
                                    }) {
                                        Text("Retry instrument", color = Color(0xFF92400E))
                                    }
                                }
                            }
                        }

                        state.tasks.forEachIndexed { index, task ->
                            StepRouter(
                                stepNumber = index + 1,
                                task = task,
                                onComplete = {
                                    screenModel.onEvent(ActiveQuestEvent.CompleteTask(index))
                                }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (state.allTasksCompleted) {
                    Button(
                        onClick = { screenModel.onEvent(ActiveQuestEvent.SubmitQuest) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudUpload,
                            contentDescription = "Upload",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Upload Data & Complete Quest",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                } else {
                    TextButton(onClick = {
                        screenModel.onEvent(ActiveQuestEvent.ShowEndQuestConfirmation)
                    }) {
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
    }
}

@Composable
private fun ActiveQuestTopBar(
    title: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFFFFF))
            .displayCutoutPadding()
            .statusBarsPadding()
            .padding(top = 16.dp)
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

