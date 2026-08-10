package sk.martinvanco.monad.quests.presentation.components.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.presentation.components.QuestStepCard

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
    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        // No content slot: QuestStepCard already renders the description, and an informational
        // step has no step-specific UI of its own. Passing it here as well printed every briefing
        // twice — invisible while descriptions were empty, obvious the moment they were plumbed in.
        actions = {
            TextBoxActions(onContinue = onComplete)
        }
    )
}

/**
 * Actions section: Continue button
 */
@Composable
private fun TextBoxActions(onContinue: () -> Unit) {
    Button(
        onClick = onContinue,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF5B6ECC)
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Continue",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}
