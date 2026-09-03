package sk.martinvanco.monad.quests.presentation.components.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.compose.koinInject
import sk.martinvanco.monad.facts.data.FactDto
import sk.martinvanco.monad.facts.domain.FactDeck
import sk.martinvanco.monad.facts.presentation.DwellFactPanel
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.data.dto.TaskConfigParser
import sk.martinvanco.monad.quests.data.dto.WaitConfig
import sk.martinvanco.monad.quests.presentation.components.QuestStepCard

/**
 * Wait/Timer step component
 * Displays countdown timer and auto-completes when time expires
 */
@Composable
fun WaitStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    factDeck: FactDeck = koinInject()
) {
    val config = remember(task) {
        TaskConfigParser.getWaitConfig(task)
    }

    val totalSeconds = config?.timeoutSeconds ?: 0
    var remainingSeconds by remember { mutableStateOf(totalSeconds) }
    var isRunning by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    // Reading matter for the wait (IP-146), on the same grounds as the probe dwell: a timer this
    // step cannot shorten is an interval, and an interval is either boring or it teaches something.
    var factOrder by remember { mutableStateOf<List<FactDto>>(emptyList()) }

    LaunchedEffect(totalSeconds) {
        if (totalSeconds <= 0) return@LaunchedEffect
        val panels = (totalSeconds / WAIT_PANEL_SECONDS) + 2
        factOrder = factDeck.runningOrder(factDeck.all(), panels.coerceAtLeast(3))
    }

    // Auto-complete when timer reaches 0
    LaunchedEffect(remainingSeconds) {
        if (remainingSeconds == 0 && isRunning) {
            delay(500) // Small delay for visual feedback
            onComplete()
        }
    }

    // Timer countdown logic
    LaunchedEffect(isRunning, isPaused) {
        if (isRunning && !isPaused) {
            while (isActive && remainingSeconds > 0) {
                delay(1000)
                remainingSeconds--
            }
        }
    }

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        content = {
            WaitContent(
                remainingSeconds = remainingSeconds,
                totalSeconds = totalSeconds,
                isRunning = isRunning,
                facts = factOrder
            )
        },
        actions = {
            WaitActions(
                isRunning = isRunning,
                isPaused = isPaused,
                remainingSeconds = remainingSeconds,
                onStart = {
                    isRunning = true
                    isPaused = false
                },
                onPause = {
                    isPaused = true
                },
                onResume = {
                    isPaused = false
                }
            )
        }
    )
}

/**
 * Content section: Timer visualization
 */
@Composable
private fun WaitContent(
    remainingSeconds: Int,
    totalSeconds: Int,
    isRunning: Boolean,
    facts: List<FactDto>
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Warning message
        if (isRunning) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFEF3C7), // amber-100
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️",
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Please do not close the app while the timer is running",
                        fontSize = 13.sp,
                        color = Color(0xFF92400E), // amber-800
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Progress bar
        if (isRunning) {
            LinearProgressIndicator(
                progress = { (totalSeconds - remainingSeconds).toFloat() / totalSeconds.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF5B6ECC),
                trackColor = Color(0xFFE2E8F0)
            )
        }

        // Simple timer display at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            val timeColor = when {
                remainingSeconds <= 5 && isRunning -> Color(0xFFEF4444) // red - urgent
                remainingSeconds <= 10 && isRunning -> Color(0xFFF59E0B) // amber - warning
                isRunning -> Color(0xFF0F172A) // dark
                else -> Color(0xFF64748B) // gray
            }

            Text(
                text = formatTime(if (isRunning) remainingSeconds else totalSeconds),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = timeColor
            )
        }

        // Only while the clock is running. Before that the step is a briefing the participant has
        // to read, and a second reading pane under it would compete with the instruction.
        if (isRunning) {
            DwellFactPanel(facts = facts, panelSeconds = WAIT_PANEL_SECONDS)
        }
    }
}

/** How long one fact stays up. Same eleven seconds as the probe dwell, for the same reasons. */
private const val WAIT_PANEL_SECONDS = 11

/**
 * Actions section: Start/Pause/Resume buttons
 */
@Composable
private fun WaitActions(
    isRunning: Boolean,
    isPaused: Boolean,
    remainingSeconds: Int,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    when {
        !isRunning -> {
            // Not started yet
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5B6ECC)
                ),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Start Timer",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
        isPaused -> {
            // Timer is paused
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF22C55E) // green-500
                ),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Resume",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
        remainingSeconds == 0 -> {
            // Timer completed
            Button(
                onClick = {},
                enabled = false,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF22C55E)
                ),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Complete!",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
        else -> {
            // Timer is running
            Button(
                onClick = onPause,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF59E0B) // amber-500
                ),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Pause",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}

// ============================================================================
// Helper Functions
// ============================================================================

/**
 * Format seconds into MM:SS format
 */
private fun formatTime(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    val minutesStr = if (minutes < 10) "0$minutes" else "$minutes"
    val secsStr = if (secs < 10) "0$secs" else "$secs"
    return "$minutesStr:$secsStr"
}
