package sk.martinvanco.monad.quests.presentation.components.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import sk.martinvanco.monad.lab.data.LabSessionRepository
import sk.martinvanco.monad.lab.domain.HeadcountMarkerPayload
import sk.martinvanco.monad.lab.domain.HeadcountSweepEvent
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.SessionMarker
import sk.martinvanco.monad.lab.domain.SweepPhase
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.data.dto.ObserveConfig
import sk.martinvanco.monad.quests.data.dto.SweepRoom
import sk.martinvanco.monad.quests.data.dto.TaskConfigParser
import sk.martinvanco.monad.quests.presentation.components.QuestStepCard

/**
 * IP-140 / IP-162 — the headcount widget.
 *
 * Two contracts share this step and are never mixed. A config with no `schema` is the **legacy
 * partial view**: look up, count the people you can see from where you stand, record it, several
 * times over, views overlapping. A config naming `monad-quest/observe/v2` is a **room sweep**:
 * walk one named room once, count each other person once, record the running total at checkpoints,
 * then a final total with what was and was not covered. A config naming anything else refuses to
 * run rather than guess which of the two the author meant.
 *
 * **This is the only channel in the lab that counts people.** Every other stream observes a
 * handset and shares one blind spot — anybody without the app. So the number here is never
 * reconciled against the BLE count: the disagreement between them is the penetration bias, and
 * measuring it is the point.
 *
 * The sweep keeps no count in Compose state. Every tap is an event appended through
 * [HeadcountSweepController], and the number on screen is the replay of what is durably in the log.
 * A tap that failed to record says so and offers a retry under the same event identity; a second
 * tap while one is in flight is refused. The counter below the log is the participant's scratch
 * value for the *next* checkpoint and nothing more.
 *
 * **How many spots is up to the participant** (2026-09-23). The first live Counting quest asked for
 * five viewpoint readings and a researcher who could see the room from two spots had to invent
 * three more. Neither contract needs a fixed number: a legacy step's `min_readings` is now the
 * suggested number of spots and *Done* is offered after the first reading, with `of_readings` still
 * recording what was suggested so a reader can tell a short set from a full one. A sweep has no
 * minimum at all — checkpoints are optional, and *Count from one spot* starts the sweep and goes
 * straight to the final total. What a single spot covered is what the participant declares: the
 * whole room, or a named part of it, in which case the reference is for that part and says so.
 */
@Composable
fun ObserveStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: (stepData: String?) -> Unit,
    modifier: Modifier = Modifier,
    instrument: LabInstrument = koinInject(),
    repository: LabSessionRepository = koinInject(),
) {
    val config = remember(task) { TaskConfigParser.getObserveConfig(task) }
    val instrumentState by instrument.state.collectAsState()
    // A running session and a phone on air are two different facts, and this step used to check
    // only the first. The fleet's BLE record is the ONLY thing that says where a reading was taken,
    // so a count recorded while the frame was off is a number with no position.
    val broadcasting by instrument.isBroadcasting.collectAsState(initial = false)
    val broadcastExpected = instrumentState.request?.broadcast == true

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        content = {
            when {
                config == null -> ObserveNotice(
                    "This step has no counting configuration. It cannot run — tell the operator.",
                    Color(0xFFFEE2E2),
                    Color(0xFF991B1B),
                )

                config.isUnsupported -> ObserveNotice(
                    "This counting step uses a contract this app build does not know " +
                        "(${config.schema} / ${config.mode}). Update the app; the step cannot run here.",
                    Color(0xFFFEE2E2),
                    Color(0xFF991B1B),
                )

                config.isSweep -> SweepContent(
                    config = config,
                    task = task,
                    sessionRunning = instrumentState.isRunning,
                    silent = broadcastExpected && !broadcasting,
                    onAir = if (broadcastExpected) broadcasting else null,
                    instrument = instrument,
                    repository = repository,
                    onComplete = onComplete,
                )

                else -> LegacyObserveContent(
                    config = config,
                    sessionRunning = instrumentState.isRunning,
                    silent = broadcastExpected && !broadcasting,
                    broadcasting = broadcasting,
                    instrument = instrument,
                    onComplete = { onComplete(null) },
                )
            }
        },
        actions = {},
    )
}

// ---- the room sweep (IP-162) --------------------------------------------------------------------

@Composable
private fun SweepContent(
    config: ObserveConfig,
    task: ActiveTaskDto,
    sessionRunning: Boolean,
    silent: Boolean,
    /** What the app observes of its own radio; null when the quest never asked for the frame. */
    onAir: Boolean?,
    instrument: LabInstrument,
    repository: LabSessionRepository,
    onComplete: (stepData: String?) -> Unit,
) {
    val stepCompletionId = task.stepCompletionId
    val enrollmentId = instrument.state.value.request?.enrollmentId
    val problems = remember(config) { config.sweepProblems() }
    if (stepCompletionId == null || enrollmentId.isNullOrBlank() || problems.isNotEmpty()) {
        ObserveNotice(
            when {
                problems.isNotEmpty() -> "This sweep configuration is incomplete (${problems.joinToString()}). Tell the operator."
                stepCompletionId == null -> "This step carries no completion identity, so its counts could not be attributed. Restart the quest."
                else -> "No enrollment is attached to the measurement session. Restart the quest."
            },
            Color(0xFFFEE2E2),
            Color(0xFF991B1B),
        )
        return
    }

    val controller = remember(stepCompletionId) { HeadcountSweepController(instrument, repository) }
    LaunchedEffect(stepCompletionId, sessionRunning) {
        controller.attach(enrollmentId, stepCompletionId, config)
    }
    val ui by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    // The participant's scratch value for the NEXT checkpoint. Not the sweep state: that is the
    // replayed log above, and the two are shown apart on purpose.
    var counter by remember(stepCompletionId) { mutableStateOf(0) }
    var seeded by remember(stepCompletionId) { mutableStateOf(false) }
    if (!seeded && ui.attached) {
        counter = ui.sweep.runningTotal ?: 0
        seeded = true
    }
    var selectedRoom by remember(stepCompletionId) { mutableStateOf<SweepRoom?>(config.rooms.singleOrNull()) }
    var observers by remember(stepCompletionId) { mutableStateOf<Int?>(1) }
    var mode by remember(stepCompletionId) { mutableStateOf(SweepMode.COUNTING) }
    var reason by remember(stepCompletionId) { mutableStateOf("") }
    var coverage by remember(stepCompletionId) { mutableStateOf(HeadcountSweepEvent.COVERAGE_COMPLETE) }
    var stability by remember(stepCompletionId) { mutableStateOf("stable") }
    var activity by remember(stepCompletionId) { mutableStateOf("unknown") }
    var duplicateRisk by remember(stepCompletionId) { mutableStateOf("none_observed") }
    var completed by remember(stepCompletionId) { mutableStateOf(false) }
    // The participant chose to count from where they stand. The sweep is the same two events
    // (start, finalise); only the screen skips the checkpoint stage and asks what the spot covered.
    var singleSpot by remember(stepCompletionId) { mutableStateOf(false) }

    val ceiling = config.maxCount
    val busy = ui.pending || ui.canRetry
    val phase = ui.sweep.phase

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            !sessionRunning || ui.noSession -> ObserveNotice(
                "The measurement session is not running, so your counts cannot be recorded. " +
                    "Retry the instrument from the warning banner, or tell the operator.",
                Color(0xFFFEE2E2),
                Color(0xFF991B1B),
            )

            silent -> ObserveNotice(
                "Your phone is not on air, so the receivers cannot tell where you are. Keep the app " +
                    "open and the screen on. Your counts are still recorded.",
                Color(0xFFFEF3C7),
                Color(0xFF92400E),
            )
        }
        if (ui.recovered && phase == SweepPhase.ACTIVE) {
            ObserveNotice(
                "This sweep was resumed from ${ui.sweep.eventsAccepted} recorded event(s). If people moved " +
                    "while the app was away, save it as partial and start again.",
                Color(0xFFFEF3C7),
                Color(0xFF92400E),
            )
        }
        ui.lastError?.let {
            ObserveNotice(it, Color(0xFFFEE2E2), Color(0xFF991B1B))
            if (ui.canRetry) {
                OutlinedButton(
                    enabled = !ui.pending,
                    onClick = { scope.launch { controller.retry() } },
                    shape = RoundedCornerShape(6.dp),
                ) { Text("Retry recording", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (!ui.sweep.isValid) {
            ObserveNotice(
                "The recorded events disagree with the sweep rules (${ui.sweep.errors.joinToString { it.code }}). " +
                    "What you counted is kept; this run cannot become a room reference.",
                Color(0xFFFEE2E2),
                Color(0xFF991B1B),
            )
        }

        when (phase) {
            SweepPhase.NOT_STARTED -> {
                Text(
                    text = "Count every other person once. Do not count yourself. Walk the room, or count " +
                        "what you can see from one spot — as many spots as you need, no minimum.",
                    fontSize = 15.sp,
                    color = Color(0xFF0F172A),
                    textAlign = TextAlign.Center,
                )
                if (config.rooms.size > 1) {
                    config.rooms.forEach { room ->
                        ChoiceRow(
                            label = room.label,
                            selected = selectedRoom?.roomId == room.roomId,
                            onSelect = { selectedRoom = room },
                        )
                    }
                }
                selectedRoom?.let {
                    Text(
                        text = (if (config.rooms.size == 1) "${it.label}. " else "") + it.coverageInstructions,
                        fontSize = 13.sp,
                        color = Color(0xFF334155),
                        textAlign = TextAlign.Center,
                    )
                }
                ObserversPicker(observers, onChange = { observers = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = selectedRoom != null && sessionRunning && !busy,
                        onClick = {
                            val room = selectedRoom ?: return@Button
                            singleSpot = false
                            scope.launch { controller.start(room, observers, onAir) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                        shape = RoundedCornerShape(6.dp),
                    ) { Text("Walk the room", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                    OutlinedButton(
                        enabled = selectedRoom != null && sessionRunning && !busy,
                        onClick = {
                            val room = selectedRoom ?: return@OutlinedButton
                            scope.launch {
                                controller.start(room, observers, onAir).onSuccess {
                                    singleSpot = true
                                    coverage = HeadcountSweepEvent.COVERAGE_PARTIAL
                                    reason = ""
                                    mode = SweepMode.FINISHING
                                }
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                    ) { Text("Count from one spot", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }

            SweepPhase.ACTIVE -> {
                Text(
                    text = config.prompt,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A),
                    textAlign = TextAlign.Center,
                )
                Counter(
                    count = counter,
                    onDecrement = { if (counter > 0) counter -= 1 },
                    onIncrement = { if (ceiling == null || counter < ceiling) counter += 1 },
                    enabled = !busy,
                )
                val recordedSoFar = ui.sweep.runningTotal
                if (mode != SweepMode.FINISHING || !singleSpot) {
                    Text(
                        text = when {
                            recordedSoFar == null -> "No checkpoint yet. Checkpoints are optional: record one when you " +
                                "stop somewhere, or go straight to the final total."
                            else -> "Recorded so far: $recordedSoFar people, ${ui.sweep.checkpoints.size} checkpoint(s)" +
                                (if (ui.sweep.corrections > 0) ", ${ui.sweep.corrections} correction(s)" else "")
                        },
                        fontSize = 13.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center,
                    )
                }
                when (mode) {
                    SweepMode.COUNTING -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = { mode = SweepMode.FINISHING },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                                shape = RoundedCornerShape(6.dp),
                            ) { Text("Finish with $counter", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                            OutlinedButton(
                                enabled = sessionRunning && !busy,
                                onClick = { scope.launch { controller.checkpoint(counter, onAir) } },
                                shape = RoundedCornerShape(6.dp),
                            ) { Text("Checkpoint $counter", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ui.sweep.correctableEventId != null) {
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = { reason = ""; mode = SweepMode.CORRECTING },
                                    shape = RoundedCornerShape(6.dp),
                                ) { Text("Correct last", fontSize = 14.sp) }
                            }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { reason = ""; mode = SweepMode.ABORTING },
                                shape = RoundedCornerShape(6.dp),
                            ) { Text("Stop, keep as partial", fontSize = 14.sp) }
                        }
                    }

                    SweepMode.CORRECTING -> {
                        Text(
                            "Set the counter to the corrected total and say why. The original checkpoint is kept.",
                            fontSize = 13.sp, color = Color(0xFF334155), textAlign = TextAlign.Center,
                        )
                        ReasonField(reason, onChange = { reason = it }, label = "Reason for the correction")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = reason.isNotBlank() && !busy,
                                onClick = {
                                    scope.launch {
                                        controller.correctLast(counter, reason, onAir).onSuccess { mode = SweepMode.COUNTING }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                                shape = RoundedCornerShape(6.dp),
                            ) { Text("Record correction to $counter", fontSize = 14.sp, color = Color.White) }
                            OutlinedButton(onClick = { mode = SweepMode.COUNTING }, shape = RoundedCornerShape(6.dp)) {
                                Text("Back", fontSize = 14.sp)
                            }
                        }
                    }

                    SweepMode.FINISHING -> {
                        Text(
                            if (singleSpot) {
                                "Set the counter to everyone you can see from here, then say what this spot covers."
                            } else {
                                "Final total: $counter other people. Now say what this sweep covered."
                            },
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A), textAlign = TextAlign.Center,
                        )
                        ChoiceGroup(
                            title = "What did you cover?",
                            options = HeadcountSweepEvent.COVERAGES,
                            selected = coverage,
                            label = ::coverageLabel,
                        ) { coverage = it }
                        if (coverage != HeadcountSweepEvent.COVERAGE_COMPLETE) {
                            PartChips(current = reason, onPick = { reason = it })
                            ReasonField(
                                reason,
                                onChange = { reason = it },
                                label = if (coverage == HeadcountSweepEvent.COVERAGE_PARTIAL) {
                                    "Which part of the room did you count?"
                                } else {
                                    "Why is the coverage unknown?"
                                },
                            )
                            Text(
                                "The reference will be for that part only, not for the whole room.",
                                fontSize = 12.sp, color = Color(0xFF64748B), textAlign = TextAlign.Center,
                            )
                        }
                        ChoiceGroup("Did people come or go during the sweep?", HeadcountSweepEvent.STABILITIES, stability) { stability = it }
                        ChoiceGroup("What were people doing?", HeadcountSweepEvent.ACTIVITIES, activity) { activity = it }
                        ChoiceGroup("Could anyone have been counted twice?", HeadcountSweepEvent.DUPLICATE_RISKS, duplicateRisk) { duplicateRisk = it }
                        ObserversPicker(observers, onChange = { observers = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy && (coverage == HeadcountSweepEvent.COVERAGE_COMPLETE || reason.isNotBlank()),
                                onClick = {
                                    scope.launch {
                                        controller.finalise(
                                            count = counter,
                                            coverage = coverage,
                                            coverageReason = reason,
                                            occupancyStability = stability,
                                            activity = activity,
                                            duplicateRisk = duplicateRisk,
                                            observersInArea = observers,
                                            onAir = onAir,
                                        ).onSuccess { mode = SweepMode.COUNTING }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                                shape = RoundedCornerShape(6.dp),
                            ) { Text("Save final total $counter", fontSize = 14.sp, color = Color.White) }
                            OutlinedButton(
                                onClick = { singleSpot = false; mode = SweepMode.COUNTING },
                                shape = RoundedCornerShape(6.dp),
                            ) { Text(if (singleSpot) "Walk instead" else "Back", fontSize = 14.sp) }
                        }
                    }

                    SweepMode.ABORTING -> {
                        Text(
                            "The counts recorded so far are kept as a partial observation. Say why the sweep stops here.",
                            fontSize = 13.sp, color = Color(0xFF334155), textAlign = TextAlign.Center,
                        )
                        ReasonField(reason, onChange = { reason = it }, label = "Why the sweep is incomplete")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = reason.isNotBlank() && !busy,
                                onClick = {
                                    scope.launch {
                                        controller.abort(reason, onAir).onSuccess { mode = SweepMode.COUNTING }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF92400E)),
                                shape = RoundedCornerShape(6.dp),
                            ) { Text("Save as partial", fontSize = 14.sp, color = Color.White) }
                            OutlinedButton(onClick = { mode = SweepMode.COUNTING }, shape = RoundedCornerShape(6.dp)) {
                                Text("Back", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            SweepPhase.FINALISED, SweepPhase.ABORTED -> {
                val sweep = ui.sweep
                Text(
                    text = if (phase == SweepPhase.FINALISED) {
                        "Final total ${sweep.finalCount} other people. Covered: ${coverageLabel(sweep.coverage.orEmpty()).lowercase()}" +
                            (sweep.coverageReason?.let { " ($it)" } ?: "") + ". Occupancy ${sweep.occupancyStability}."
                    } else {
                        "Saved as partial: ${sweep.runningTotal ?: 0} people counted so far. ${sweep.abortReason.orEmpty()}"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Recorded on this phone (${sweep.eventsAccepted} events). It uploads with the session; " +
                        "the operator's console shows when it is verified.",
                    fontSize = 13.sp,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center,
                )
                if (!completed) {
                    Button(
                        onClick = {
                            completed = true
                            onComplete(controller.stepDataJson())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                        shape = RoundedCornerShape(6.dp),
                    ) { Text("Done", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                }
            }
        }
    }
}

private enum class SweepMode { COUNTING, CORRECTING, FINISHING, ABORTING }

@Composable
private fun Counter(count: Int, onDecrement: () -> Unit, onIncrement: () -> Unit, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CounterButton("−", onDecrement, enabled = enabled && count > 0)
        Text(
            text = "$count",
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A),
            modifier = Modifier.widthIn(min = 96.dp),
            textAlign = TextAlign.Center,
        )
        CounterButton("+", onIncrement, enabled = enabled)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, fontSize = 14.sp, color = Color(0xFF0F172A))
    }
}

@Composable
private fun ChoiceGroup(
    title: String,
    options: List<String>,
    selected: String,
    label: (String) -> String = { it.replace('_', ' ') },
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF334155))
        options.forEach { option ->
            ChoiceRow(label = label(option), selected = option == selected, onSelect = { onSelect(option) })
        }
    }
}

/** The wire value stays `complete | partial | unknown`; the screen says what each means. */
private fun coverageLabel(coverage: String): String = when (coverage) {
    HeadcountSweepEvent.COVERAGE_COMPLETE -> "The whole room"
    HeadcountSweepEvent.COVERAGE_PARTIAL -> "Part of the room"
    HeadcountSweepEvent.COVERAGE_UNKNOWN -> "Not sure"
    else -> coverage
}

/**
 * Quick descriptions of which part of a room one spot covers. Each tap sets the reason text, which
 * stays editable: the participant can name the part in their own words, and the text is what the
 * `coverage_reason` field carries. A chip is a shortcut, not a vocabulary — the contract keeps free text
 * so a room with an unusual layout is not forced into the wrong half.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartChips(current: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PART_CHOICES.forEach { choice ->
            FilterChip(
                selected = current == choice,
                onClick = { onPick(choice) },
                label = { Text(choice, fontSize = 12.sp) },
            )
        }
    }
}

private val PART_CHOICES = listOf(
    "Left half",
    "Right half",
    "Near the entrance",
    "Far end",
    "Window side",
    "One row of desks",
    "What I could see from the door",
)

@Composable
private fun ObserversPicker(value: Int?, onChange: (Int?) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "How many people are counting in this room right now, you included?",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF334155),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1, 2, 3).forEach { n ->
                RadioButton(selected = value == n, onClick = { onChange(n) })
                Text("$n", fontSize = 14.sp)
            }
            RadioButton(selected = value == null, onClick = { onChange(null) })
            Text("don't know", fontSize = 14.sp)
        }
    }
}

@Composable
private fun ReasonField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = false,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- the legacy partial view (IP-140), unchanged in contract ----------------------------------

@Composable
private fun LegacyObserveContent(
    config: ObserveConfig,
    sessionRunning: Boolean,
    /** The session asked for the identity frame and the phone is not putting it on air. */
    silent: Boolean,
    broadcasting: Boolean,
    instrument: LabInstrument,
    onComplete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf(0) }
    var recorded by remember { mutableStateOf(0) }
    var hasCompleted by remember { mutableStateOf(false) }
    var lastRecorded by remember { mutableStateOf<Int?>(null) }
    val required = config.minReadings
    val ceiling = config.maxCount

    fun record() {
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
                        prompt = config.prompt,
                        // Read at the instant Record was pressed, not at step entry: an iOS
                        // handset drops off air the moment the app backgrounds, and the reading
                        // after that is the one that needs the flag.
                        onAir = broadcasting,
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            required <= 0 -> ObserveNotice(
                "This step has no counting configuration. It cannot run — tell the operator.",
                Color(0xFFFEE2E2),
                Color(0xFF991B1B),
            )

            !sessionRunning -> ObserveNotice(
                "The measurement session is not running, so your counts will not be recorded. " +
                    "Retry the instrument from the warning banner, or tell the operator.",
                Color(0xFFFEE2E2),
                Color(0xFF991B1B),
            )

            silent -> ObserveNotice(
                "Your phone is not on air, so the receivers cannot tell where these counts were " +
                    "taken. Keep the app open and the screen on. The numbers are still recorded.",
                Color(0xFFFEF3C7),
                Color(0xFF92400E),
            )
        }

        config.prompt.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF0F172A),
                textAlign = TextAlign.Center,
            )
        }

        Counter(
            count = count,
            onDecrement = { if (count > 0) count -= 1 },
            onIncrement = { if (ceiling == null || count < ceiling) count += 1 },
            enabled = true,
        )

        if (required > 0) {
            LinearProgressIndicator(
                progress = { (recorded.coerceAtMost(required)).toFloat() / required.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF22C55E),
                trackColor = Color(0xFFE2E8F0),
            )
            Text(
                text = when {
                    recorded == 0 -> "Count what you can see from here and record it. The quest suggests " +
                        "$required spots; stop when you have covered what you can."
                    recorded < required -> "$recorded recorded, $required suggested" +
                        (lastRecorded?.let { " · last was $it" } ?: "") + ". Done whenever you are."
                    else -> "$recorded recorded — enough, and more is welcome"
                },
                fontSize = 13.sp,
                color = Color(0xFF64748B),
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { record() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = "Record $count",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                // Available after the FIRST reading, and then always. `min_readings` is the
                // suggested number of spots, not a bar: a participant who can see the room from
                // two spots should not invent three more, and one who wants ten should not be
                // stopped. The payload's `of_readings` still says what was suggested, so a
                // reader tells a two-reading set from a five-reading one.
                if (recorded >= 1 && !hasCompleted) {
                    OutlinedButton(
                        onClick = {
                            hasCompleted = true
                            onComplete()
                        },
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(if (recorded < required) "Done with $recorded" else "Done", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
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
