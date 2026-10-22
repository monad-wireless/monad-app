package dev.dubec.monad.core.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One line of the lab's sense of humour, tap for another.
 *
 * The lines are the public site's footer (`monad_knowledge/web/static/js/eggs.js`), kept to its
 * rule: the facts are real and the framing is absurd, so a student may quote any factual clause and
 * be right. Copied rather than fetched — the joke has to work offline, in a library basement.
 */
@Composable
fun EggLine(modifier: Modifier = Modifier, color: Color = Color(0xFF94A3B8)) {
    var index by rememberSaveable { mutableStateOf(EGGS.indices.random()) }
    AnimatedContent(
        targetState = index,
        transitionSpec = { (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }) togetherWith fadeOut(tween(150)) },
        label = "egg",
        modifier = modifier.fillMaxWidth(),
    ) { shown ->
        androidx.compose.material3.Text(
            text = EGGS[shown],
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontStyle = FontStyle.Italic,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { index = (shown + 1 + (0 until EGGS.size - 1).random()) % EGGS.size }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

private val EGGS = listOf(
    "In space, no one can hear you scream. On channel 11, the receivers can.",
    "Wi-Fi does not stand for anything. A branding agency made the name up in 1999.",
    "Frequency hopping was patented in 1942 by a film star, Hedy Lamarr, and a composer, George Antheil.",
    "The answer is 42. The question is how many people are in the room. We are still working on the question.",
    "People are roughly 60% water, and radio notices water. That is the whole method. The rest is a thesis.",
    "Channel 14 exists. It is legal only in Japan, and only for 802.11b. It is the Area 51 of Wi-Fi.",
    "Radio covers about 30 cm per nanosecond. Our timestamps are less punctual, and they know it.",
    "Time is an illusion. Lunchtime doubly so.",
    "The fleet has no access point. You cannot connect to it. It can, however, hear you not connecting.",
    "Bluetooth is named after Harald Bluetooth, a tenth-century king who united Denmark and Norway.",
    "Microwave ovens and Wi-Fi share the 2.4 GHz band. Lunch is a measurable event.",
    "People walk at about 1.3 m/s. Hardcore punk plays faster. Only one of these is in the thesis.",
    "So long, and thanks for all the packets.",
    "A towel is the most useful thing a hitchhiker can have. A spare microSD card is a close second.",
    "Crowd counting without cameras: the only safe way to count a mosh pit without joining it.",
    "No camera, no microphone. Mostly harmless.",
    "Don't panic. Keep the app open.",
)
