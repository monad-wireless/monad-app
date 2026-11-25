package dev.dubec.monad.quests.presentation.quest_detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform.getKoin
import dev.dubec.monad.core.presentation.components.ScreenWithBackNavigation
import dev.dubec.monad.quests.data.dto.QuestDetailDto
import dev.dubec.monad.quests.presentation.active_quest.ActiveQuestScreen

/**
 * One quest, before it starts: what you do, why it helps, what to remember, and one button.
 *
 * This is the only place a run starts. The page used to list the raw steps, which for a route is
 * forty names that answer none of those questions. [QuestOutline] says the same in two lines.
 */
data class QuestDetailScreen(val questId: String) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = remember {
            getKoin().get<QuestDetailScreenModel> { parametersOf(questId) }
        }
        val state by screenModel.state.collectAsState()

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
                title = { Text(text = "This quest cannot start now", fontWeight = FontWeight.SemiBold) },
                text = { Text(text = state.startQuestError ?: "An error occurred") },
                confirmButton = {
                    TextButton(
                        onClick = { screenModel.onEvent(QuestDetailEvent.DismissStartQuestError) }
                    ) {
                        Text(text = "OK", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }

        ScreenWithBackNavigation(
            title = "Quest",
            onBackClick = { navigator.pop() }
        ) {
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Accent)
                }

                state.error != null -> Box(
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
                            color = Muted,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = { screenModel.onEvent(QuestDetailEvent.RetryLoad) }) {
                            Text(text = "Try again", color = Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                else -> state.quest?.let { quest ->
                    QuestDetailContent(
                        quest = quest,
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
        isStartingQuest: Boolean,
        onStartClick: () -> Unit
    ) {
        val outline = remember(quest) { QuestOutline.of(quest.tasks.map { it.type }) }

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = quest.name,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    letterSpacing = (-0.8).sp,
                    lineHeight = 36.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quest.duration?.let { Figure("about $it min") }
                    if (quest.points > 0f) Figure("${quest.points.toInt()} points", emphasis = true)
                }

                quest.imageUrl?.let { imageUrl ->
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

                if (quest.description.isNotBlank()) {
                    Text(
                        text = quest.description,
                        fontSize = 17.sp,
                        lineHeight = 25.sp,
                        color = Ink,
                    )
                }

                if (outline.actions.isNotEmpty()) {
                    Section("What you will do") {
                        outline.actions.forEachIndexed { index, action ->
                            ActionRow(number = index + 1, text = action)
                        }
                    }
                }

                outline.purpose?.let { purpose ->
                    Section("Why it helps") { Body(purpose) }
                }

                Section("Please remember") {
                    Body(
                        "Keep the app open and the screen on until the quest ends. You can stop at " +
                            "any time with \"End quest\"."
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = onStartClick,
                    enabled = !isStartingQuest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        disabledContainerColor = Accent.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isStartingQuest) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isStartingQuest) "Starting…" else "Start",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF1F5F9))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
            content()
        }
    }

    @Composable
    private fun Body(text: String) {
        Text(text = text, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFF334155))
    }

    @Composable
    private fun ActionRow(number: Int, text: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE0E7FF)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "$number", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent)
            }
            Text(text = text, fontSize = 15.sp, lineHeight = 21.sp, color = Ink)
        }
    }

    @Composable
    private fun Figure(text: String, emphasis: Boolean = false) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasis) Color(0xFF2A1A00) else Color(0xFF334155),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (emphasis) Color(0xFFFFC24D) else Color(0xFFE2E8F0))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

private val Accent = Color(0xFF5B6ECC)
private val Ink = Color(0xFF0F142F)
private val Muted = Color(0xFF64748B)
