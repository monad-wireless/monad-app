package sk.martinvanco.monad.facts.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sk.martinvanco.monad.facts.data.FactDto

/**
 * What a participant reads while they hold still (IP-146).
 *
 * The dwell is the app's worst screen and the one hardest to change: thirty seconds is near the
 * *ceiling* of a self-consistent CSI fingerprint, so it is a physical constraint rather than a
 * setting, and the participant has been told not to move. What used to be there was a countdown.
 * A cohort that finds the protocol boring does not finish it, and an unfinished protocol is the
 * expensive failure here — a half-walked fingerprint quest leaves surveyed points with no
 * measurement and no way to know which.
 *
 * So the panel is deliberately the loudest thing on the step: a dark reading pane against the
 * light card, one fact at a time, rotated on a timer and advanceable by tap. Every word in it is a
 * verbatim paragraph of a curated Foundation card — see `edu/deck_facts.py`.
 *
 * @param facts the running order for this dwell, from `FactDeck.runningOrder`.
 * @param panelSeconds how long one fact stays up before the next slides in.
 */
@Composable
fun DwellFactPanel(
    facts: List<FactDto>,
    modifier: Modifier = Modifier,
    panelSeconds: Int = 11,
) {
    if (facts.isEmpty()) return

    var index by remember(facts) { mutableStateOf(0) }

    // Rotation. Keyed on the index as well as the list so a tap restarts the dwell rather than
    // showing the tapped fact for whatever was left of the previous panel's timer.
    LaunchedEffect(facts, index) {
        delay(panelSeconds * 1000L)
        index = (index + 1) % facts.size
    }

    val fact = facts[index]
    val egg = fact.surprise

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (egg) EggBrush else PaneBrush)
            .border(
                width = if (egg) 1.5.dp else 0.dp,
                color = if (egg) EggEdge else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable { index = (index + 1) % facts.size }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlavourChip(fact)
            Text(
                text = "tap for the next one",
                fontSize = 11.sp,
                color = Color(0xFF8C97C8),
            )
        }

        AnimatedContent(
            targetState = fact,
            transitionSpec = {
                (fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 6 }) togetherWith
                    (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 6 })
            },
            label = "fact",
        ) { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = shown.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = if (shown.surprise) EggInk else Color.White,
                )
                Text(
                    text = withEmphasis(shown.body),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = Color(0xFFD8DEF6),
                )
            }
        }

        if (facts.size > 1) {
            PanelDots(count = facts.size, current = index, egg = egg)
        }
    }
}

/**
 * "Foundation" / "In the wild" / "Oddity", and the third one is the point.
 *
 * An oddity is a curator-marked card whose worked example carries a number absurd enough to be
 * worth telling somebody: a coin cell that outlasts a degree, a receiver that threw away
 * thirty-four million packets, twenty megahertz that cannot tell two paths apart unless they are
 * fifteen metres different. The chip glows so a participant learns to hope for it.
 */
@Composable
private fun FlavourChip(fact: FactDto) {
    val (label, ink, ground) = when {
        fact.surprise -> Triple("⚡ ODDITY", Color(0xFF3B1D00), Color(0xFFFFC24D))
        fact.flavour == "wild" -> Triple("IN THE WILD", Color(0xFF04241A), Color(0xFF5EEAD4))
        else -> Triple("FOUNDATION", Color(0xFFE8ECFF), Color(0xFF3D4C9E))
    }

    // A marked oddity pulses. Nothing else on the dwell screen moves except the countdown, so a
    // slow breath here reads as "look at this" without competing with the number.
    val pulse = if (fact.surprise) {
        rememberInfiniteTransition(label = "eggPulse").animateFloat(
            initialValue = 0.72f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
            label = "eggAlpha",
        ).value
    } else {
        1f
    }

    Text(
        text = label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = ink,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ground.copy(alpha = pulse))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** Where this fact sits in the dwell's running order. */
@Composable
private fun PanelDots(count: Int, current: Int, egg: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .size(if (i == current) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            i != current -> Color(0xFF3A4478)
                            egg -> Color(0xFFFFC24D)
                            else -> Color(0xFF8FA2FF)
                        }
                    ),
            )
        }
    }
}

/**
 * Render the deck notes' `**bold**` spans.
 *
 * The curated text puts its numbers in bold, and those numbers are the reason the fact is worth
 * reading — "**98.4% of commanded frames delivered**". Stripping the markers would flatten exactly
 * the words the curator marked as load-bearing, so the panel honours them and nothing else: no
 * other markdown is interpreted, because the exporter guarantees prose.
 */
internal fun withEmphasis(body: String): AnnotatedString = buildAnnotatedString {
    val parts = body.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(part) }
        } else {
            append(part)
        }
    }
    // An odd number of markers means an unclosed span. `split` already handled it by treating the
    // tail as plain text, which is the right failure: the participant sees the sentence.
}

private val PaneBrush = Brush.linearGradient(
    colors = listOf(Color(0xFF1B2350), Color(0xFF141A3A)),
    start = Offset.Zero,
    end = Offset(600f, 900f),
)

private val EggBrush = Brush.linearGradient(
    colors = listOf(Color(0xFF2E1F4E), Color(0xFF17203F)),
    start = Offset.Zero,
    end = Offset(600f, 900f),
)

private val EggEdge = Color(0xFFFFC24D)
private val EggInk = Color(0xFFFFD98A)
