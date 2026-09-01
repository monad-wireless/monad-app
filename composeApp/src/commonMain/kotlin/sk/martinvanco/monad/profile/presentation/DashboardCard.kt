package sk.martinvanco.monad.profile.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One panel of the profile dashboard.
 *
 * The card language is taken from the sibling app, `repos/monad-defense`
 * (`Views/StatsView.swift`): a vertical scroll of rounded panels on a slightly grey ground,
 * each headed by a small semibold secondary label. Copied deliberately rather than
 * reinvented — the two apps are the field instrument and the study companion, they already
 * share one mark and one accent (`#5B6ECC`), and a screenshot of either should sit beside
 * the other without an argument about which is which.
 *
 * `cornerRadius: 20` and `padding: 16` are that app's values, not new ones.
 */
@Composable
fun DashboardCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF64748B),
        )
        content()
    }
}

/** House accent, shared with monad-defense's `Theme.accent`. */
val MonadAccent = Color(0xFF5B6ECC)

/** House ink, shared with monad-defense's `Theme.ink`. */
val MonadInk = Color(0xFF0F142F)
