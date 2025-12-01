package sk.martinvanco.monad.quests.presentation.active_quest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
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
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform.getKoin
import sk.martinvanco.monad.quests.presentation.end_quest.EndQuestScreen
import sk.martinvanco.monad.quests.presentation.components.StepRouter

data class ActiveQuestScreen(
    val questId: String
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = remember {
            getKoin().get<ActiveQuestScreenModel> { parametersOf(questId) }
        }
        val state by screenModel.state.collectAsState()

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            ActiveQuestTopBar(
                title = state.questName,
                isBleCollecting = state.isBleCollecting,
                bleRecordCount = state.bleRecordCount
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                state.tasks.forEachIndexed { index, task ->
                    StepRouter(
                        stepNumber = index + 1,
                        task = task,
                        onComplete = {
                            screenModel.onEvent(ActiveQuestEvent.CompleteTask(index))
                        },
                        onReportIssue = {
                            screenModel.onEvent(ActiveQuestEvent.ReportIssue(index))
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                TextButton(onClick = {
                    screenModel.onEvent(ActiveQuestEvent.EndQuest)
                    navigator.push(EndQuestScreen(questId = questId, questName = state.questName))
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

@Composable
private fun ActiveQuestTopBar(
    title: String,
    isBleCollecting: Boolean = false,
    bleRecordCount: Long = 0
) {
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
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
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

                // BLE Status indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isBleCollecting) Color(0xFFDCFCE7) else Color(0xFFF3F4F6))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sensors,
                        contentDescription = "BLE",
                        tint = if (isBleCollecting) Color(0xFF22C55E) else Color(0xFF9CA3AF),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "$bleRecordCount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isBleCollecting) Color(0xFF166534) else Color(0xFF6B7280)
                    )
                }
            }
        }
    }
}

