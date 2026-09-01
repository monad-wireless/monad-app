package sk.martinvanco.monad.profile.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sk.martinvanco.monad.profile.data.dto.ActivityDayDto

/**
 * Dwells per day over six weeks, as bars.
 *
 * Drawn with layout rather than a Canvas because the shape is a row of rectangles and a
 * chart library would be a dependency for one figure.
 *
 * **Every day is a bar, including the empty ones.** The server sends zeros rather than
 * omitting quiet days, and this must keep them: dropping empties compresses a fortnight of
 * nothing into a solid week and turns a gap into a streak. That is the same rule the public
 * site keeps for a curve, where a gap is `null` and never a zero carried forward — here the
 * zero is real and the omission would be the lie.
 */
@Composable
fun ActivityChart(days: List<ActivityDayDto>, modifier: Modifier = Modifier) {
    val peak = days.maxOfOrNull { it.dwells } ?: 0
    if (days.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEach { day ->
                // A day with dwells is never shorter than a hairline, so "one measurement"
                // and "none" are distinguishable. Scaling alone would make a 1 against a
                // peak of 30 round to nothing.
                val fraction = if (peak == 0) 0f else day.dwells.toFloat() / peak
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(if (day.dwells > 0) maxOf(fraction, 0.08f) else 0.02f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (day.dwells > 0) MonadAccent else Color(0xFFE2E8F0)),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.first().date.takeLast(5), fontSize = 11.sp, color = Color(0xFF94A3B8))
            Text("peak $peak", fontSize = 11.sp, color = Color(0xFF94A3B8))
            Text("today", fontSize = 11.sp, color = Color(0xFF94A3B8))
        }
    }
}
