package sk.martinvanco.monad.quests.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.data.dto.TaskStatus

/**
 * The focus rail: one open step, everything else a one-line row.
 *
 * The active-quest screen used to render every step as a full card in one scroll. That
 * is fine for a three-step quest and unusable for the treasure hunt, which has
 * twenty-two: a participant standing in a library with a phone in one hand had to
 * scroll a wall of cards to find the one they were on, and the two nearly-identical
 * cards either side of it are the ones they would tap by mistake.
 *
 * So the list keeps its shape and collapses. Done steps become a tick and a title,
 * upcoming ones a number and a title, and the step you are on is the only card open.
 *
 * **Not a LazyColumn, deliberately.** A collapsed row is cheap, but the open card is
 * running a countdown — a probe's dwell, an advertise timer — inside its own
 * composition. A lazy list disposes what scrolls off screen, which would silently kill
 * the timer the moment a participant scrolled to read the step above. The parent stays
 * a plain `Column` with `verticalScroll` for exactly that reason.
 */
@Composable
fun QuestStepProgress(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (done >= total) "All steps done" else "Step ${done + 1} of $total",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF0F172A),
            )
            if (done < total) {
                Text(
                    text = "${total - done} to go",
                    fontSize = 13.sp,
                    color = Color(0xFF64748B),
                )
            }
        }
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total.toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Color(0xFF22C55E),
            trackColor = Color(0xFFE2E8F0),
        )
    }
}

/**
 * A collapsed step — a tick or a number, and the title.
 *
 * Tappable, and that is not decoration. Collapsing by default gives a participant one
 * thing to do; making the rows inert would take away the escape hatch the old
 * all-cards-open screen accidentally provided, and a step that cannot be completed
 * (a probe with no session, a card nobody has put on a wall) would strand the run
 * with nothing to press but End Quest.
 */
@Composable
fun CollapsedStepRow(
    stepNumber: Int,
    task: ActiveTaskDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDone = task.status == TaskStatus.COMPLETED
    val isTrouble = task.status == TaskStatus.FAILED || task.status == TaskStatus.SKIPPED

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(if (isTrouble) Color(0xFFFEF2F2) else Color(0xFFF8FAFC))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isDone -> Color(0xFF22C55E)
                        isTrouble -> Color(0xFFEF4444)
                        else -> Color(0xFFE2E8F0)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isDone) "✓" else "$stepNumber",
                fontSize = if (isDone) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDone || isTrouble) Color.White else Color(0xFF64748B),
            )
        }
        Text(
            text = task.name,
            fontSize = 14.sp,
            // Done steps recede; upcoming ones stay readable, because "what am I doing
            // after this" is a question a participant asks while walking.
            color = if (isDone) Color(0xFF94A3B8) else Color(0xFF475569),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
