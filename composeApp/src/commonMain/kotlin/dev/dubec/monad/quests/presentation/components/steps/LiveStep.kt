package dev.dubec.monad.quests.presentation.components.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dubec.monad.core.util.currentTimeMillis
import dev.dubec.monad.facts.data.FactDto
import dev.dubec.monad.facts.domain.DwellState
import dev.dubec.monad.facts.domain.FactDeck
import dev.dubec.monad.facts.presentation.DwellFactPanel
import dev.dubec.monad.lab.domain.LabInstrument
import dev.dubec.monad.lab.domain.Phase
import dev.dubec.monad.lab.domain.monotonicNanos
import dev.dubec.monad.quests.data.dto.ActiveTaskDto
import dev.dubec.monad.quests.data.dto.TaskStatus
import dev.dubec.monad.quests.domain.live.LiveAir
import dev.dubec.monad.quests.domain.live.LiveAnswer
import dev.dubec.monad.quests.domain.live.LiveGateway
import dev.dubec.monad.quests.domain.live.LiveRecorder
import dev.dubec.monad.quests.domain.live.LiveStepController
import dev.dubec.monad.quests.domain.live.LiveWidget
import dev.dubec.monad.quests.presentation.components.QuestStepCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * A step whose screen the server drives.
 *
 * It starts only when a recording runs, because an event without a recording is evidence of
 * nothing, and it runs only while it is the active step: two live steps of one quest must not poll
 * at the same time.
 */
@Composable
fun LiveStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: (stepData: String?) -> Unit,
    modifier: Modifier = Modifier,
    instrument: LabInstrument = koinInject(),
    gateway: LiveGateway = koinInject(),
    recorder: LiveRecorder = koinInject(),
) {
    val instrumentState by instrument.state.collectAsState()
    val broadcasting by instrument.isBroadcasting.collectAsState(initial = false)
    val onAir by rememberUpdatedState(broadcasting)
    val seenCard by instrument.seenCard.collectAsState()
    val trackerOwnsCamera = remember(instrumentState.isRunning) { instrument.posePreviewHandle() != null }
    val scope = rememberCoroutineScope()
    val finish by rememberUpdatedState(onComplete)

    val stepId = task.stepCompletionId
    val active = task.status == TaskStatus.ACTIVE
    val controller = remember(stepId) {
        stepId?.let {
            LiveStepController(
                stepCompletionId = it,
                gateway = gateway,
                recorder = recorder,
                // The UUID of what is really on air. A phone that fell silent has no subject.
                air = { LiveAir(subject = instrument.broadcast?.serviceUuid?.takeIf { onAir }, broadcasting = onAir) },
                wallMillis = ::currentTimeMillis,
                monotonicNanos = ::monotonicNanos,
            )
        }
    }
    val state = controller?.state?.collectAsState()?.value
    var finished by remember(stepId) { mutableStateOf(false) }

    LaunchedEffect(controller, active, instrumentState.isRunning) {
        if (active && instrumentState.isRunning) controller?.run()
    }

    QuestStepCard(
        stepNumber = stepNumber,
        title = state?.screen?.title?.takeIf { active && it.isNotBlank() } ?: task.name,
        description = if (state?.screen == null) task.description else "",
        status = task.status,
        modifier = modifier,
        content = if (!active) null else {
            {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val starting = instrumentState.phase != Phase.IDLE && !instrumentState.isRunning
                    when {
                        controller == null -> Note("This step is set up wrong. Tell the organiser.", Color(0xFF991B1B))
                        state?.refusedStatus == 401 -> Note("Please log in again and open the quest.", Color(0xFF991B1B))
                        state?.refusedStatus != null ->
                            Note("The server closed this step (HTTP ${state.refusedStatus}). End the quest and start it again.", Color(0xFF991B1B))
                        starting -> Note("Getting ready… a few seconds.", Color(0xFF3730A3))
                        !instrumentState.isRunning -> Note("Recording is off. Tap Retry in the banner at the top.", Color(0xFF991B1B))
                        state?.offline == true -> Note("No connection. Trying again…", Color(0xFF92400E))
                        state?.screen == null -> Note("Connecting…", Color(0xFF3730A3))
                    }

                    val usable = controller != null && state?.refusedStatus == null && instrumentState.isRunning
                    if (usable) {
                        state?.screen?.widgets?.forEach { widget ->
                            LiveWidgetView(
                                widget = widget,
                                answered = widget is LiveWidget.Input && widget.id in state.answered,
                                trackerOwnsCamera = trackerOwnsCamera,
                                seenCard = seenCard,
                                onAnswer = { input, answer -> scope.launch { controller?.submit(input, answer) } },
                                hold = { hold ->
                                    HoldInput(stepNumber, hold, recorder) { seconds ->
                                        scope.launch { controller?.submit(hold, LiveAnswer.Held(seconds)) }
                                    }
                                },
                            )
                        }
                        // The last screen is the result, so the participant closes it, not a timer.
                        if (state?.screen?.done == true) {
                            ActionButton("Continue", primary = true, enabled = !finished) {
                                finished = true
                                finish(state.screen.resultJson)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text = text, fontSize = 14.sp, lineHeight = 20.sp, color = color)
}

/** Counts down and brackets the stillness in the recording, as a probe's dwell does. */
@Composable
private fun HoldInput(
    stepNumber: Int,
    hold: LiveWidget.Hold,
    recorder: LiveRecorder,
    factDeck: FactDeck = koinInject(),
    onHeld: (Int) -> Unit,
) {
    var left by remember { mutableIntStateOf(hold.seconds) }
    var facts by remember { mutableStateOf<List<FactDto>>(emptyList()) }

    LaunchedEffect(Unit) {
        facts = factDeck.runningOrder(factDeck.all(), (hold.seconds / PANEL_SECONDS + 2).coerceAtLeast(3), metaStep = stepNumber)
    }
    LaunchedEffect(Unit) {
        hold.target?.let { recorder.holdStarted(it) }
        while (left > 0) {
            delay(1_000)
            left--
        }
        hold.target?.let { recorder.holdEnded(it, hold.seconds) }
        onHeld(hold.seconds)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$left s", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
        Text(hold.label, fontSize = 15.sp, color = Color(0xFF334155))
        LinearProgressIndicator(
            progress = { (hold.seconds - left).toFloat() / hold.seconds },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = Color(0xFF5B6ECC),
            trackColor = Color(0xFFE2E8F0),
        )
        DwellFactPanel(
            facts = facts,
            panelSeconds = PANEL_SECONDS,
            dwellState = DwellState(stepNumber = stepNumber, dwellSeconds = hold.seconds, heldSeconds = hold.seconds - left, panelsShown = 0),
        )
    }
}

private const val PANEL_SECONDS = 11
