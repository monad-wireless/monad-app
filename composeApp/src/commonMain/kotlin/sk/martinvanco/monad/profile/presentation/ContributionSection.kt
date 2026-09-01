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
import sk.martinvanco.monad.profile.data.dto.HistoryEntryDto
import sk.martinvanco.monad.profile.data.dto.ProfileStatsDto

/**
 * The participant's dashboard (IP-145).
 *
 * A vertical scroll of panels in the sibling app's card language — see [DashboardCard]. Four
 * of them, in this order, and the order is the argument:
 *
 *  1. **Coverage.** The library with the points this person has stood at filled in. The
 *     signature, and the one picture a step-counting app cannot draw: the surveyed set is
 *     finite and known, so coverage is a fact rather than a metaphor and the gaps are the
 *     invitation to walk again.
 *  2. **Measurements over six weeks.** The same window the study app charts.
 *  3. **Totals.** Time standing still first, because that is the act this instrument values
 *     and the inversion that makes it not a fitness app. Points last: the score is a
 *     measured intervention with no evidence behind it yet (EXP-012), and the minutes are
 *     true whatever that finds.
 *  4. **Recent quests.**
 */
@Composable
fun ContributionSection(
    stats: ProfileStatsDto?,
    isLoading: Boolean,
    error: String?,
    siteUrl: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(strokeWidth = 2.dp) }

            error != null || stats == null -> DashboardCard("Your contribution") {
                // No figures beside an error. A dashboard showing a stale total under a red
                // banner is the screenshot somebody believes.
                Text("Could not load this.", fontSize = 15.sp, color = Color(0xFF475569))
                TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                    Text("Try again")
                }
            }

            stats.contribution.dwells == 0 -> DashboardCard("Nothing measured yet") {
                // An empty screen is an invitation to act, not a wall of zeros.
                Text(
                    text = "Scan any marked point in the library and stand still for thirty " +
                        "seconds. That is one real measurement, and it is the shortest one " +
                        "this lab can take.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Color(0xFF64748B),
                )
            }

            else -> {
                DashboardCard("Coverage") {
                    AsyncImage(
                        model = "$siteUrl/lab/coverage.svg?visited=" +
                            stats.contribution.pointsVisited.joinToString(","),
                        contentDescription = "The library, with the points you have stood at filled in",
                        modifier = Modifier.fillMaxWidth().height(215.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        text = "${stats.contribution.distinctPoints} of 35 surveyed points",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MonadInk,
                    )
                }

                DashboardCard("Measurements · last 6 weeks") {
                    ActivityChart(stats.activity)
                }

                DashboardCard("Totals") {
                    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        Figure(formatDuration(stats.contribution.dwellSeconds), "standing still")
                        Figure(stats.contribution.dwells.toString(), "measurements")
                        Figure(formatPoints(stats.pointsTotal), "points")
                    }
                    Text(
                        text = "Each measurement is a moment when the radio was recorded and " +
                            "somebody knew exactly where you were. That pairing is the experiment.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFF94A3B8),
                    )
                }

                if (stats.history.isNotEmpty()) {
                    DashboardCard("Recent quests") {
                        stats.history.take(8).forEach { HistoryRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = MonadInk)
        Text(label, fontSize = 11.sp, color = Color(0xFF94A3B8))
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntryDto) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.quest, fontSize = 14.sp, color = MonadInk, modifier = Modifier.weight(1f))
        entry.completedAt?.let {
            Text(it.take(10), fontSize = 12.sp, color = Color(0xFF94A3B8))
        }
        Spacer(Modifier.width(10.dp))
        // An enrollment from before the ledger carries no frozen award, and an em-dash says
        // so rather than printing a zero it did not earn.
        Text(
            text = entry.points?.let { formatPoints(it) } ?: "—",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MonadAccent,
        )
    }
}

private fun formatPoints(points: Float): String =
    if (points % 1f == 0f) points.toInt().toString() else points.toString()

private fun formatDuration(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} m"
}
