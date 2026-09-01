package sk.martinvanco.monad.profile.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import sk.martinvanco.monad.profile.data.dto.ContributionDto
import sk.martinvanco.monad.profile.data.dto.HistoryEntryDto

/**
 * What this participant has done (IP-145).
 *
 * Deliberately two halves, in this order: the contribution block sits ABOVE the history and
 * carries the same visual weight as the score. The score is a measured intervention
 * (EXP-012) and the corpus holds no evidence that it helps; "you held still at 34 surveyed
 * points for 17 minutes" is true whatever that experiment finds.
 */
class ProfileScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<ProfileScreenModel>()
        val state by screenModel.state.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Your contribution",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
            )

            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.error != null -> ErrorCard(state.error!!, onRetry = screenModel::load)

                state.stats != null -> {
                    val stats = state.stats!!

                    // The contribution first. It answers "what did my walking produce"
                    // rather than "what is my score", and it is the half that is true
                    // regardless of what EXP-012 finds about the score.
                    ContributionCard(stats.contribution)

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile("Points", formatPoints(stats.pointsTotal), Modifier.weight(1f))
                        StatTile("Quests", stats.questsCompleted.toString(), Modifier.weight(1f))
                    }

                    if (stats.history.isNotEmpty()) {
                        Text(
                            text = "History",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0F172A),
                        )
                        stats.history.forEach { HistoryRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributionCard(contribution: ContributionDto) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFE2E8FD))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${contribution.dwells} measurements",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF5B6ECC),
        )
        Text(
            text = "at ${contribution.distinctPoints} different surveyed points, " +
                "${formatDuration(contribution.dwellSeconds)} of standing still in total.",
            fontSize = 14.sp,
            color = Color(0xFF334155),
        )
        Text(
            text = "Every one of those is a moment where the radio was recorded and somebody " +
                "knew exactly where you were standing. That pairing is the whole experiment.",
            fontSize = 13.sp,
            color = Color(0xFF64748B),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF1F5F9))
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
        Text(label, fontSize = 13.sp, color = Color(0xFF64748B))
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntryDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF8FAFC))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.quest, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF0F172A))
            entry.completedAt?.let {
                Text(it.take(10), fontSize = 12.sp, color = Color(0xFF94A3B8))
            }
        }
        // An older completion carries no frozen award, and an em-dash says so rather than
        // printing a zero it did not earn.
        Text(
            text = entry.points?.let { formatPoints(it) } ?: "—",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF5B6ECC),
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFEF2F2))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Could not load your stats.",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFDC2626),
            textAlign = TextAlign.Center,
        )
        Text(message, fontSize = 13.sp, color = Color(0xFF991B1B), textAlign = TextAlign.Center)
        Button(onClick = onRetry, shape = RoundedCornerShape(6.dp)) { Text("Try again") }
    }
}

private fun formatPoints(points: Float): String =
    if (points % 1f == 0f) points.toInt().toString() else points.toString()

private fun formatDuration(seconds: Int): String = when {
    seconds < 60 -> "$seconds seconds"
    seconds < 3600 -> "${seconds / 60} minutes"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}
