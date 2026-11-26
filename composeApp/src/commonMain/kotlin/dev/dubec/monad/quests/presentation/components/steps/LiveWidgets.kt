package dev.dubec.monad.quests.presentation.components.steps

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dubec.monad.quests.domain.live.LiveAnswer
import dev.dubec.monad.quests.domain.live.LiveWidget
import dev.dubec.monad.quests.presentation.components.CardScanner
import dev.dubec.monad.quests.presentation.components.ScannerDialog

private val Ink = Color(0xFF0F172A)
private val Muted = Color(0xFF64748B)
private val Indigo = Color(0xFF5B6ECC)

/** One widget the server asked for. Local input is kept per widget id and dropped with it. */
@Composable
internal fun LiveWidgetView(
    widget: LiveWidget,
    answered: Boolean,
    trackerOwnsCamera: Boolean,
    seenCard: String?,
    onAnswer: (LiveWidget.Input, LiveAnswer) -> Unit,
    hold: @Composable (LiveWidget.Hold) -> Unit,
) {
    when (widget) {
        is LiveWidget.Text -> TextLine(widget)
        is LiveWidget.Meter -> MeterView(widget)
        is LiveWidget.Stats -> StatsView(widget)
        is LiveWidget.Progress -> ProgressLine(widget)
        is LiveWidget.Scan -> key(widget.id) { ScanInput(widget, trackerOwnsCamera, seenCard) { onAnswer(widget, LiveAnswer.Scanned(it)) } }
        is LiveWidget.Number -> key(widget.id) { NumberInput(widget, !answered) { onAnswer(widget, LiveAnswer.Counted(it)) } }
        is LiveWidget.Choice -> key(widget.id) { ChoiceInput(widget, !answered) { onAnswer(widget, LiveAnswer.Chosen(it)) } }
        is LiveWidget.Hold -> key(widget.id) { hold(widget) }
        is LiveWidget.Button -> key(widget.id) { ActionButton(widget.label, widget.primary, !answered) { onAnswer(widget, LiveAnswer.Tapped) } }
    }
}

@Composable
private fun TextLine(widget: LiveWidget.Text) {
    val (ink, ground) = when (widget.style) {
        LiveWidget.Text.Style.BODY -> Ink to Color.Transparent
        LiveWidget.Text.Style.HINT -> Muted to Color.Transparent
        LiveWidget.Text.Style.WARNING -> Color(0xFF92400E) to Color(0xFFFEF3C7)
        LiveWidget.Text.Style.SUCCESS -> Color(0xFF166534) to Color(0xFFDCFCE7)
    }
    val boxed = ground != Color.Transparent
    Text(
        text = widget.text,
        fontSize = if (widget.style == LiveWidget.Text.Style.HINT) 13.sp else 15.sp,
        lineHeight = 21.sp,
        color = ink,
        modifier = Modifier.fillMaxWidth()
            .then(if (boxed) Modifier.clip(RoundedCornerShape(8.dp)).background(ground).padding(12.dp) else Modifier),
    )
}

/** Colour, word and arrow say the same thing, so the meter never rests on colour alone. */
@Composable
private fun MeterView(widget: LiveWidget.Meter) {
    val color by animateColorAsState(
        when (widget.tone) {
            LiveWidget.Meter.Tone.NONE -> Color(0xFF94A3B8)
            LiveWidget.Meter.Tone.COLD -> Color(0xFF2563EB)
            LiveWidget.Meter.Tone.COOL -> Color(0xFF0891B2)
            LiveWidget.Meter.Tone.WARM -> Color(0xFFD97706)
            LiveWidget.Meter.Tone.HOT -> Color(0xFFDC2626)
        }
    )
    val level by animateFloatAsState(widget.value ?: 0f)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(168.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (widget.trend) {
                    LiveWidget.Meter.Trend.UP -> Text("▲", fontSize = 26.sp, color = Color.White)
                    LiveWidget.Meter.Trend.DOWN -> Text("▼", fontSize = 26.sp, color = Color.White)
                    else -> Unit
                }
                Text(
                    text = widget.label,
                    fontSize = if (widget.label.length > 10) 18.sp else 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
        if (widget.value != null) {
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = color,
                trackColor = Color(0xFFE2E8F0),
            )
        }
    }
}

@Composable
private fun StatsView(widget: LiveWidget.Stats) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        widget.items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.label, fontSize = 15.sp, color = Muted)
                Text(item.value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            }
        }
    }
}

@Composable
private fun ProgressLine(widget: LiveWidget.Progress) {
    Text("${widget.value} of ${widget.total} ${widget.label}", fontSize = 13.sp, color = Muted)
}

@Composable
private fun ScanInput(widget: LiveWidget.Scan, trackerOwnsCamera: Boolean, seenCard: String?, onCode: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // The step scrolls, so an inline camera opened below the meter sat half off screen. With the
    // camera free, the scanner is a full-screen view over the step. While the pose tracker owns
    // the camera there is no preview to show, only a line of text, so that case stays inline.
    if (open && !trackerOwnsCamera) {
        ScannerDialog(title = widget.label, hint = widget.hint, error = null, onClose = { open = false }) { cameraModifier ->
            CardScanner(
                trackerOwnsCamera = false,
                seenCard = null,
                onCode = { code ->
                    open = false
                    onCode(code)
                },
                modifier = cameraModifier,
                fill = true,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (open && trackerOwnsCamera) {
            if (widget.hint.isNotBlank()) Text(widget.hint, fontSize = 13.sp, color = Muted)
            CardScanner(
                trackerOwnsCamera = trackerOwnsCamera,
                seenCard = seenCard,
                onCode = { code ->
                    open = false
                    onCode(code)
                },
            )
            ActionButton("Close camera", primary = false, enabled = true) { open = false }
        } else {
            ActionButton(widget.label, primary = true, enabled = !open) { open = true }
        }
    }
}

@Composable
private fun NumberInput(widget: LiveWidget.Number, enabled: Boolean, onSubmit: (Int) -> Unit) {
    var value by remember { mutableStateOf(widget.initial) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(widget.prompt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            OutlinedButton(onClick = { value-- }, enabled = enabled && value > widget.min, shape = CircleShape, modifier = Modifier.size(64.dp)) {
                Text("−", fontSize = 24.sp)
            }
            Text("$value", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Ink)
            OutlinedButton(onClick = { value++ }, enabled = enabled && value < widget.max, shape = CircleShape, modifier = Modifier.size(64.dp)) {
                Text("+", fontSize = 24.sp)
            }
        }
        // A crowd of thirty is not thirty taps.
        if (widget.max - widget.min > 20) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                OutlinedButton(onClick = { value = (value - 5).coerceAtLeast(widget.min) }, enabled = enabled && value > widget.min) { Text("− 5") }
                OutlinedButton(onClick = { value = (value + 5).coerceAtMost(widget.max) }, enabled = enabled && value < widget.max) { Text("+ 5") }
            }
        }
        ActionButton("Save $value", primary = true, enabled = enabled) { onSubmit(value) }
    }
}

@Composable
private fun ChoiceInput(widget: LiveWidget.Choice, enabled: Boolean, onSubmit: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(widget.prompt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        widget.options.forEach { option ->
            ActionButton(option.label, primary = false, enabled = enabled) { onSubmit(option.value) }
        }
    }
}

@Composable
internal fun ActionButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(label, fontSize = 15.sp)
        }
    }
}
