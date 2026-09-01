package sk.martinvanco.monad.profile.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import sk.martinvanco.monad.profile.data.dto.ProfileStatsDto

/**
 * What this person's walking produced, shown in their profile (IP-145).
 *
 * THE DESIGN IDEA, stated so a later edit does not quietly undo it.
 *
 * Every step-counting app leads with a big number and a ring. This one must not, for a
 * reason specific to the subject: here the valuable act is **holding still**, not moving,
 * and the surveyed set is **finite and known** — 35 points on this floor. So coverage is a
 * fact rather than a metaphor, and the plan of the room IS the number. A person reads their
 * own progress off its shape, and the hollow points are the invitation to walk again.
 *
 * Everything around that plan stays quiet. Two figures, not four tiles. No gradient, no
 * ring, no confetti: the score is a measured intervention with no evidence behind it yet
 * (EXP-012), while "you stood still for seventeen minutes" is true whatever that finds.
 */
@Composable
fun ContributionSection(
    stats: ProfileStatsDto?,
    isLoading: Boolean,
    error: String?,
    totalPoints: Int,
    siteUrl: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Your contribution",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = Color(0xFF94A3B8),
        )

        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(strokeWidth = 2.dp) }

            error != null || stats == null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF8FAFC))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // No numbers beside an error. A profile showing a stale total under a red
                // banner is the screenshot somebody believes.
                Text("Could not load this.", fontSize = 15.sp, color = Color(0xFF475569))
                TextButton(onClick = onRetry) { Text("Try again") }
            }

            stats.contribution.dwells == 0 -> EmptyState()

            else -> {
                // The plan first, and largest. It is the hero and the statistic at once.
                AsyncImage(
                    model = "$siteUrl/lab/coverage.svg?visited=" +
                        stats.contribution.pointsVisited.joinToString(","),
                    contentDescription = "The library, with the points you have stood at filled in",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp),
                    contentScale = ContentScale.Fit,
                )

                Text(
                    text = "${stats.contribution.distinctPoints} of 35 points in this library",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A),
                )

                // Two figures, and the first is the one that inverts the genre. Standing
                // still is the measurement; the score is a number we are still testing.
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Figure(formatDuration(stats.contribution.dwellSeconds), "standing still")
                    Figure(stats.contribution.dwells.toString(), "measurements")
                    Figure(totalPoints.toString(), "points")
                }

                Text(
                    text = "Each of those is a moment when the radio was recorded and somebody " +
                        "knew exactly where you were. That pairing is the experiment.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Color(0xFF64748B),
                )
            }
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
        Text(label, fontSize = 12.sp, color = Color(0xFF94A3B8))
    }
}

@Composable
private fun EmptyState() {
    // An empty screen is an invitation to act, not a zero.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF1F5F9))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Nothing measured yet",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF0F172A),
        )
        Text(
            text = "Scan any marked point in the library and stand still for thirty seconds. " +
                "That is one real measurement, and it is the shortest one this lab can take.",
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = Color(0xFF64748B),
        )
    }
}

private fun formatDuration(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} m"
}
