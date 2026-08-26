package sk.martinvanco.monad.quests.presentation.components.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import sk.martinvanco.monad.lab.domain.HeadcountMarkerPayload
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.SessionMarker
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.data.dto.ObserveConfig
import sk.martinvanco.monad.quests.data.dto.TaskConfigParser
import sk.martinvanco.monad.quests.presentation.components.QuestStepCard

/**
 * IP-140 — the headcount widget: look up, count the people you can see, record it.
 *
 * One step, many readings. The participant walks the room and records a count
 * wherever they stop, and each one lands on the session timeline as a `headcount`
 * marker on the same clock as the radio. That is what makes it joinable: the fleet
 * already knows where the phone was at that instant, so nobody has to ask the
 * person where they were standing.
 *
 * **This is the only channel in the lab that counts people.** Every other stream
 * observes a handset and shares one blind spot — anybody without the app. So the
 * number here is never reconciled against the BLE count: the disagreement between
 * them is the penetration bias, and measuring it is the point.
 *
 * The widget is deliberately dull. A big number, a minus, a plus, and a record
 * button. Counting people while walking is already the hard part, and every extra
 * control is one more thing to get wrong one-handed while looking at a room rather
 * than at a screen.
 */
@Composable
fun ObserveStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    instrument: LabInstrument = koinInject(),
) {
    val config = remember(task) { TaskConfigParser.getObserveConfig(task) }
    val instrumentState by instrument.state.collectAsState()
    val scope = rememberCoroutineScope()

    var count by remember { mutableStateOf(0) }
    var recorded by remember { mutableStateOf(0) }
    var hasCompleted by remember { mutableStateOf(false) }
    var lastRecorded by remember { mutableStateOf<Int?>(null) }

    val required = config?.minReadings ?: 0
    val ceiling = config?.maxCount

    fun record() {
        val cfg = config ?: return
        val reading = recorded + 1
        scope.launch {
            instrument.mark(
                kind = SessionMarker.Kind.HEADCOUNT,
                label = "$count",
                stepId = reading.toString(),
                payload = Json.encodeToString(
                    HeadcountMarkerPayload.serializer(),
                    HeadcountMarkerPayload(
                        count = count,
                        reading = reading,
                        ofReadings = required,
                        prompt = cfg.prompt,
                    ),
                ),
            )
        }
        recorded = reading
        lastRecorded = count
        // The counter is NOT reset. The next spot is usually a small change from
        // this one, and re-entering the whole number from zero each time is how a
        // tired participant starts guessing instead of counting.
    }

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        content = {
            ObserveContent(
                config = config,
                sessionRunning = instrumentState.isRunning,
                count = count,
                recorded = recorded,
                required = required,
                lastRecorded = lastRecorded,
                onDecrement = { if (count > 0) count -= 1 },
                onIncrement = { if (ceiling == null || count < ceiling) count += 1 },
            )
        },
        actions = {
            if (config != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { record() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = "Record ${count}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                    // Available only once the step's own bar is cleared, and then
                    // always — a participant who wants to give ten readings instead
                    // of five should not be stopped, and one who has given five
                    // should not be trapped.
                    if (recorded >= required && required > 0 && !hasCompleted) {
                        OutlinedButton(
                            onClick = {
                                hasCompleted = true
                                onComplete()
                            },
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text("Done", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ObserveContent(
    config: ObserveConfig?,
    sessionRunning: Boolean,
    count: Int,
    recorded: Int,
    required: Int,
    lastRecorded: Int?,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            config == null || required <= 0 -> ObserveNotice(
                "This step has no counting configuration. It cannot run — tell the operator.",
                Color(0xFFFEE2E2),
                Color(0xFF991B1B),
            )

            // A count with no session still has a number and nowhere to put it. Unlike
            // a probe, the reading is not lost the moment it is taken — but it will
            // never reach the timeline, so the participant has to be told before they
            // spend ten minutes counting.
            !sessionRunning -> ObserveNotice(
                "The measurement session is not running, so your counts will not be recorded. " +
                    "Retry the instrument from the warning banner, or tell the operator.",
                Color(0xFFFEE2E2),
                Color(0xFF991B1B),
            )
        }

        config?.prompt?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF0F172A),
                textAlign = TextAlign.Center,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            CounterButton("−", onDecrement, enabled = count > 0)
            Text(
                text = "$count",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
                modifier = Modifier.widthIn(min = 96.dp),
                textAlign = TextAlign.Center,
            )
            CounterButton(
                "+",
                onIncrement,
                enabled = config?.maxCount == null || count < config.maxCount,
            )
        }

        if (required > 0) {
            LinearProgressIndicator(
                progress = { (recorded.coerceAtMost(required)).toFloat() / required.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF22C55E),
                trackColor = Color(0xFFE2E8F0),
            )
            Text(
                text = when {
                    recorded == 0 -> "$required spots to record"
                    recorded < required -> "$recorded of $required recorded" +
                        (lastRecorded?.let { " · last was $it" } ?: "")
                    else -> "$recorded recorded — enough, and more is welcome"
                },
                fontSize = 13.sp,
                color = Color(0xFF64748B),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CounterButton(glyph: String, onClick: () -> Unit, enabled: Boolean) {
    // Deliberately large. This is pressed one-handed, while walking, by somebody
    // looking at a room rather than at the screen.
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) Color(0xFFE2E8F0) else Color(0xFFF1F5F9),
        modifier = Modifier.size(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = glyph,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color(0xFF0F172A) else Color(0xFFCBD5E1),
            )
        }
    }
}

@Composable
private fun ObserveNotice(text: String, background: Color, foreground: Color) {
    Surface(modifier = Modifier.fillMaxWidth(), color = background, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = foreground,
            lineHeight = 18.sp,
            modifier = Modifier.padding(12.dp),
        )
    }
}
