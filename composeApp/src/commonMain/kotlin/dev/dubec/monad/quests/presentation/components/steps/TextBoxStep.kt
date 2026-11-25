package dev.dubec.monad.quests.presentation.components.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import dev.dubec.monad.core.util.currentTimeMillis
import dev.dubec.monad.quests.data.dto.ActiveTaskDto
import dev.dubec.monad.quests.data.dto.TaskConfigParser
import dev.dubec.monad.quests.presentation.components.QuestStepCard

/**
 * Text Box step component
 * Displays informational text/instructions to the user
 * No special action required except acknowledgment
 */
@Composable
fun TextBoxStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showElapsed = remember(task) { TaskConfigParser.showsElapsed(task) }
    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        // This composable renders WALK_TO, so it is the step that most needs a plan: "go to
        // Node monad07" is only actionable if the walker knows which corner that is.
        imageUrl = TaskConfigParser.stepImageUrl(task),
        // The content slot holds the count-up or nothing. QuestStepCard already renders the
        // description, and passing it here as well printed every briefing twice.
        content = if (showElapsed) {
            { ElapsedTime(startedAtMillis = task.startedAtMillis) }
        } else {
            null
        },
        actions = {
            TextBoxActions(label = TaskConfigParser.buttonLabel(task), onContinue = onComplete)
        }
    )
}

/** Time since the step began. Counts up and never ends the step: the participant does. */
@Composable
private fun ElapsedTime(startedAtMillis: Long?) {
    val start = startedAtMillis ?: return
    var now by remember { mutableStateOf(currentTimeMillis()) }
    LaunchedEffect(start) {
        while (isActive) {
            now = currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        text = TaskConfigParser.formatElapsed((now - start) / 1000),
        fontSize = 44.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF0F172A),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
}

/**
 * Actions section: one button
 */
@Composable
private fun TextBoxActions(label: String, onContinue: () -> Unit) {
    Button(
        onClick = onContinue,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF5B6ECC)
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}
