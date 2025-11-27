package sk.martinvanco.monad.quests.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sk.martinvanco.monad.quests.domain.TaskStatus

/**
 * Reusable wrapper for quest step cards
 * Provides consistent styling, animations, and layout structure
 *
 * @param stepNumber The sequential number of this step in the quest
 * @param title The step title/name
 * @param description The step description/instructions
 * @param status Current execution status of the step
 * @param content Composable slot for step-specific UI (camera, timer, etc.)
 * @param actions Composable slot for step-specific action buttons
 * @param onReportIssue Callback when user reports a problem
 */
@Composable
fun QuestStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    status: TaskStatus,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    onReportIssue: () -> Unit = {}
) {
    // Animated colors based on status
    val backgroundColor by animateColorAsState(
        targetValue = when (status) {
            TaskStatus.COMPLETED -> Color(0xFFF5F5F4) // stone-100
            TaskStatus.ACTIVE -> Color(0xFFF1F5F9) // slate-100
            TaskStatus.SCHEDULED -> Color(0xFFF1F5F9) // slate-100
        },
        animationSpec = tween(durationMillis = 300)
    )

    val numberBoxColor by animateColorAsState(
        targetValue = when (status) {
            TaskStatus.COMPLETED -> Color(0xFFEAF3EB) // light green
            TaskStatus.ACTIVE -> Color(0xFFE2E8FD) // light indigo
            TaskStatus.SCHEDULED -> Color(0xFFF2F2F2) // light gray
        },
        animationSpec = tween(durationMillis = 300)
    )

    val numberTextColor by animateColorAsState(
        targetValue = when (status) {
            TaskStatus.COMPLETED -> Color(0xFF4ADE80) // green-400
            TaskStatus.ACTIVE -> Color(0xFF5B6ECC) // indigo-500
            TaskStatus.SCHEDULED -> Color(0xFF71717A) // zinc-500
        },
        animationSpec = tween(durationMillis = 300)
    )

    val titleColor by animateColorAsState(
        targetValue = when (status) {
            TaskStatus.COMPLETED -> Color(0xFF22C55E) // green-500
            TaskStatus.ACTIVE -> Color(0xFF0F172A) // slate-900
            TaskStatus.SCHEDULED -> Color(0xFFA1A1AA) // zinc-400
        },
        animationSpec = tween(durationMillis = 300)
    )

    val descriptionColor by animateColorAsState(
        targetValue = when (status) {
            TaskStatus.COMPLETED -> Color(0xFF22C55E) // green-500
            TaskStatus.ACTIVE -> Color(0xFF0F172A) // slate-900
            TaskStatus.SCHEDULED -> Color(0xFFA1A1AA) // zinc-400
        },
        animationSpec = tween(durationMillis = 300)
    )

    val opacity = if (status == TaskStatus.COMPLETED) 0.6f else 1f

    Box(
        modifier = modifier
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
            // Step header
            StepHeader(
                stepNumber = stepNumber,
                title = title,
                description = description,
                status = status,
                numberBoxColor = numberBoxColor,
                numberTextColor = numberTextColor,
                titleColor = titleColor,
                descriptionColor = descriptionColor,
                opacity = opacity
            )

            // Content and actions (only for ACTIVE status)
            if (status == TaskStatus.ACTIVE) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Step-specific content
                    content?.invoke()

                    // Action buttons section
                    StepActions(
                        actions = actions,
                        onReportIssue = onReportIssue
                    )
                }
            }
        }
    }
}

/**
 * Step header with number badge, title, and description
 * Description is hidden for completed steps
 */
@Composable
private fun StepHeader(
    stepNumber: Int,
    title: String,
    description: String,
    status: TaskStatus,
    numberBoxColor: Color,
    numberTextColor: Color,
    titleColor: Color,
    descriptionColor: Color,
    opacity: Float
) {
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
                    text = "$stepNumber",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = numberTextColor.copy(alpha = opacity)
                )
            }

            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = titleColor.copy(alpha = opacity)
            )
        }

        // Only show description for non-completed steps
        if (status != TaskStatus.COMPLETED) {
            Text(
                text = description,
                fontSize = 14.sp,
                color = descriptionColor.copy(alpha = opacity)
            )
        }
    }
}

/**
 * Actions section with report issue button and step-specific actions
 */
@Composable
private fun StepActions(
    actions: (@Composable () -> Unit)?,
    onReportIssue: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Report issue button
        TextButton(onClick = onReportIssue) {
            Text(
                text = "Report an issue",
                fontSize = 14.sp,
                color = Color.Black,
                textDecoration = TextDecoration.Underline
            )
        }

        // Step-specific action buttons
        actions?.invoke()
    }
}
