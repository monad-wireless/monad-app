package sk.martinvanco.monad.quests.presentation.components.steps

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.scale
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
import sk.martinvanco.monad.lab.domain.Phase
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
 * **How many spots is up to the participant** (2026-09-23). Neither contract needs a fixed number:
 * a legacy step's `min_readings` is the suggested number of spots and *Done* is offered after the
 * first reading. A sweep has no minimum at all — saving a running total is optional, and *Count
 * from here* starts the sweep and goes straight to the final total.
 *
 * **Screen rules (2026-10-17, after the first field runs).** The words are short and say what to
 * press. A refused tap always says why, in place: the replay refusing a lower total used to leave
 * "Save final total" doing nothing. A counter below the last saved total is offered the correction
 * it needs instead of a refusal. The few seconds the instrument spends starting are shown as
 * starting, not as a red failure.
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
    val starting = instrumentState.phase != Phase.IDLE && !instrumentState.isRunning

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        content = {
            when {
                config == null -> ObserveNotice(
                    "This step has no counting setup. Tell the organiser.",
                    NoticeTone.ERROR,
                )

                config.isUnsupported -> ObserveNotice(
                    "This step needs a newer app. Update the app and try again.",
                    NoticeTone.ERROR,
                )

                config.isSweep -> SweepContent(
                    config = config,
                    task = task,
                    sessionRunning = instrumentState.isRunning,
                    sessionStarting = starting,
                    silent = broadcastExpected && !broadcasting,
                    onAir = if (broadcastExpected) broadcasting else null,
                    instrument = instrument,
                    repository = repository,
                    onComplete = onComplete,
                )

                else -> LegacyObserveContent(
                    config = config,
                    sessionRunning = instrumentState.isRunning,
                    sessionStarting = starting,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SweepContent(
    config: ObserveConfig,
    task: ActiveTaskDto,
    sessionRunning: Boolean,
    sessionStarting: Boolean,
    silent: Boolean,
    /** What the app observes of its own radio; null when the quest never asked for the frame. */
    onAir: Boolean?,
    instrument: LabInstrument,
    repository: LabSessionRepository,
    onComplete: (stepData: String?) -> Unit,
) {
    val stepCompletionId = task.stepCompletionId
    val problems = remember(config) { config.sweepProblems() }
    if (stepCompletionId == null || problems.isNotEmpty()) {
        ObserveNotice(
            if (problems.isNotEmpty()) {
                "This step is set up wrong (${problems.joinToString()}). Tell the organiser."
            } else {
                "This step lost its link to your run. End the quest and start it again."
            },
            NoticeTone.ERROR,
        )
        return
    }
    // The enrollment is the instrument's: it is set when the session starts. Until then the
    // step waits for the session; it is not a broken run, and saying "restart the quest" during
    // the few seconds of start-up sent people back out of a working run.
    val enrollmentId = instrument.state.value.request?.enrollmentId
    if (enrollmentId.isNullOrBlank()) {
        SessionNotice(starting = sessionStarting)
        return
    }

    val controller = remember(stepCompletionId) { HeadcountSweepController(instrument, repository) }
    LaunchedEffect(stepCompletionId, sessionRunning) {
        controller.attach(enrollmentId, stepCompletionId, config)
    }
    val ui by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    // The participant's scratch value for the NEXT saved total. Not the sweep state: that is the
    // replayed log, and the two are shown apart on purpose. Seeded from the log once the session
    // runs, so a resumed sweep starts from what it had saved.
    var counter by remember(stepCompletionId) { mutableStateOf(0) }
    var seeded by remember(stepCompletionId) { mutableStateOf(false) }
    if (!seeded && ui.attached && sessionRunning) {
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
    // (start, finalise); only the screen skips the saved-total stage.
    var singleSpot by remember(stepCompletionId) { mutableStateOf(false) }

    val ceiling = config.maxCount
    val busy = ui.pending || ui.canRetry
    val phase = ui.sweep.phase
    val saved = ui.sweep.runningTotal
    // The replay refuses a total below the last saved one unless a correction says why. The screen
    // asks for the correction before the participant presses anything that would be refused.
    val belowSaved = saved != null && counter < saved
    // The room the sweep runs in, from the replayed log first (it survives a restored session),
    // shown on every later screen when the quest offers a choice: a mis-tapped room was
    // otherwise invisible after Start (2026-10-20, a silent-room count filed as the open room).
    val sweepRoom = config.rooms.firstOrNull { it.roomId == ui.sweep.identity?.roomId } ?: selectedRoom
    val roomLabel = sweepRoom?.label?.takeIf { config.rooms.size > 1 }

    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(!sessionRunning || ui.noSession, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            SessionNotice(starting = sessionStarting)
        }
        AnimatedVisibility(sessionRunning && silent, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            ObserveNotice(
                "Your phone is not broadcasting. Keep the app open and the screen on. Your counts are still saved.",
                NoticeTone.WARNING,
            )
        }
        if (ui.recovered && phase == SweepPhase.ACTIVE) {
            ObserveNotice(
                "We restored your count. If people moved while the app was closed, stop and save as partial.",
                NoticeTone.WARNING,
            )
        }
        AnimatedVisibility(ui.lastError != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            ObserveNotice(
                text = ui.lastError.orEmpty(),
                tone = NoticeTone.ERROR,
                action = if (ui.canRetry) "Retry" else "OK",
                onAction = {
                    if (ui.canRetry) scope.launch { controller.retry() } else controller.dismissError()
                },
                actionEnabled = !ui.pending,
            )
        }
        if (!ui.sweep.isValid) {
            ObserveNotice(
                "Something in this count does not add up (${ui.sweep.errors.joinToString { it.code }}). " +
                    "Your numbers are kept, but this run will not be used as a reference.",
                NoticeTone.ERROR,
            )
        }

        AnimatedContent(
            targetState = phase,
            transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 8 }) togetherWith fadeOut(tween(150)) },
            label = "sweep-phase",
        ) { shownPhase ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (shownPhase) {
                    SweepPhase.NOT_STARTED -> {
                        Steps(
                            "Count everyone in the room once.",
                            "Do not count yourself.",
                            "Walk around, or count from one spot.",
                        )
                        if (config.rooms.size > 1) {
                            Question("Which room are you in?")
                            Chips(
                                options = config.rooms.map { it.roomId },
                                selected = selectedRoom?.roomId,
                                label = { id -> config.rooms.first { it.roomId == id }.label },
                            ) { id -> selectedRoom = config.rooms.first { it.roomId == id } }
                        } else {
                            selectedRoom?.let { Question(it.label) }
                        }
                        selectedRoom?.coverageInstructions?.takeIf { it.isNotBlank() }?.let { Hint(it) }
                        ObserversPicker(observers, onChange = { observers = it })
                        val canStart = selectedRoom != null && sessionRunning && !busy
                        PrimaryButton("Start counting", enabled = canStart) {
                            val room = selectedRoom ?: return@PrimaryButton
                            singleSpot = false
                            scope.launch { controller.start(room, observers, onAir) }
                        }
                        SecondaryButton("I see the room from here", enabled = canStart) {
                            val room = selectedRoom ?: return@SecondaryButton
                            scope.launch {
                                controller.start(room, observers, onAir).onSuccess {
                                    singleSpot = true
                                    // One spot is a partial reference by default (his rule,
                                    // 2026-09-23): the participant names the part, or picks
                                    // "Whole room" when the spot really sees all of it.
                                    coverage = HeadcountSweepEvent.COVERAGE_PARTIAL
                                    reason = ""
                                    mode = SweepMode.FINISHING
                                }
                            }
                        }
                        if (selectedRoom == null) Hint("Pick a room first.")
                    }

                    SweepPhase.ACTIVE -> {
                        roomLabel?.let { Question("Counting: $it") }
                        Hint(if (singleSpot) "People you can see from here" else config.prompt)
                        Counter(
                            count = counter,
                            onDecrement = { if (counter > 0) counter -= 1 },
                            onIncrement = { if (ceiling == null || counter < ceiling) counter += 1 },
                            enabled = !busy && mode != SweepMode.ABORTING,
                        )
                        if (!singleSpot) {
                            Hint(
                                when (saved) {
                                    null -> "Tap + for each person."
                                    else -> "Last saved: $saved" +
                                        (if (ui.sweep.corrections > 0) " · ${ui.sweep.corrections} corrected" else "")
                                },
                            )
                        }

                        AnimatedContent(
                            targetState = mode,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) },
                            label = "sweep-mode",
                        ) { shownMode ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                when (shownMode) {
                                    SweepMode.COUNTING -> CountingActions(
                                        counter = counter,
                                        saved = saved,
                                        belowSaved = belowSaved,
                                        busy = busy,
                                        sessionRunning = sessionRunning,
                                        canCorrect = ui.sweep.correctableEventId != null,
                                        onSave = { scope.launch { controller.checkpoint(counter, onAir) } },
                                        onDone = { controller.dismissError(); mode = SweepMode.FINISHING },
                                        onCorrect = { reason = ""; mode = SweepMode.CORRECTING },
                                        onRestore = { counter = saved ?: counter },
                                        onStop = { reason = ""; mode = SweepMode.ABORTING },
                                    )

                                    SweepMode.CORRECTING -> {
                                        Question("Change last saved total from ${saved ?: 0} to $counter?")
                                        ReasonField(reason, onChange = { reason = it }, label = "Why? (e.g. counted someone twice)")
                                        PrimaryButton("Save correction", enabled = reason.isNotBlank() && !busy) {
                                            scope.launch {
                                                controller.correctLast(counter, reason, onAir).onSuccess { mode = SweepMode.COUNTING }
                                            }
                                        }
                                        TextLink("Back") { mode = SweepMode.COUNTING }
                                    }

                                    SweepMode.FINISHING -> FinishForm(
                                        counter = counter,
                                        saved = saved,
                                        singleSpot = singleSpot,
                                        roomLabel = roomLabel,
                                        coverage = coverage,
                                        onCoverage = { coverage = it },
                                        reason = reason,
                                        onReason = { reason = it },
                                        stability = stability,
                                        onStability = { stability = it },
                                        activity = activity,
                                        onActivity = { activity = it },
                                        duplicateRisk = duplicateRisk,
                                        onDuplicateRisk = { duplicateRisk = it },
                                        observers = observers,
                                        onObservers = { observers = it },
                                        busy = busy,
                                        onCorrectFirst = { reason = ""; mode = SweepMode.CORRECTING },
                                        onSave = {
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
                                        onBack = { singleSpot = false; mode = SweepMode.COUNTING },
                                    )

                                    SweepMode.ABORTING -> {
                                        Question("Stop here?")
                                        Hint("What you saved so far is kept as a partial count.")
                                        ReasonField(reason, onChange = { reason = it }, label = "Why are you stopping?")
                                        PrimaryButton(
                                            "Stop and save",
                                            enabled = reason.isNotBlank() && !busy,
                                            color = Color(0xFF92400E),
                                        ) {
                                            scope.launch {
                                                controller.abort(reason, onAir).onSuccess { mode = SweepMode.COUNTING }
                                            }
                                        }
                                        TextLink("Keep counting") { mode = SweepMode.COUNTING }
                                    }
                                }
                            }
                        }
                    }

                    SweepPhase.FINALISED, SweepPhase.ABORTED -> {
                        val sweep = ui.sweep
                        DoneBadge()
                        Text(
                            text = (if (shownPhase == SweepPhase.FINALISED) {
                                "Saved: ${sweep.finalCount} ${if (sweep.finalCount == 1) "person" else "people"}"
                            } else {
                                "Saved as partial: ${sweep.runningTotal ?: 0}"
                            }) + (roomLabel?.let { "\n$it" } ?: ""),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A),
                            textAlign = TextAlign.Center,
                        )
                        Hint(
                            if (shownPhase == SweepPhase.FINALISED) {
                                coverageLabel(sweep.coverage.orEmpty()) + (sweep.coverageReason?.let { ": $it" } ?: "")
                            } else {
                                sweep.abortReason.orEmpty()
                            },
                        )
                        if (!completed) {
                            PrimaryButton("Continue") {
                                completed = true
                                onComplete(controller.stepDataJson())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountingActions(
    counter: Int,
    saved: Int?,
    belowSaved: Boolean,
    busy: Boolean,
    sessionRunning: Boolean,
    canCorrect: Boolean,
    onSave: () -> Unit,
    onDone: () -> Unit,
    onCorrect: () -> Unit,
    onRestore: () -> Unit,
    onStop: () -> Unit,
) {
    if (belowSaved) {
        // A lower total is a correction, never a silent new checkpoint: the contract keeps the
        // saved number and the reason it changed. Offer exactly that, or the way back.
        ObserveNotice("That is less than your last saved total ($saved).", NoticeTone.WARNING)
        PrimaryButton("Correct it to $counter", enabled = !busy && canCorrect, onClick = onCorrect)
        SecondaryButton("Back to $saved", enabled = !busy, onClick = onRestore)
        return
    }
    PrimaryButton("Finish: $counter ${if (counter == 1) "person" else "people"}", enabled = !busy, onClick = onDone)
    SecondaryButton(
        if (saved == counter) "Saved" else "Save progress ($counter)",
        enabled = sessionRunning && !busy && saved != counter,
        onClick = onSave,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (canCorrect) TextLink("Fix last saved", enabled = !busy, onClick = onCorrect)
        TextLink("Stop early", enabled = !busy, onClick = onStop)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinishForm(
    counter: Int,
    saved: Int?,
    singleSpot: Boolean,
    roomLabel: String?,
    coverage: String,
    onCoverage: (String) -> Unit,
    reason: String,
    onReason: (String) -> Unit,
    stability: String,
    onStability: (String) -> Unit,
    activity: String,
    onActivity: (String) -> Unit,
    duplicateRisk: String,
    onDuplicateRisk: (String) -> Unit,
    observers: Int?,
    onObservers: (Int?) -> Unit,
    busy: Boolean,
    onCorrectFirst: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    if (saved != null && counter < saved) {
        ObserveNotice("Your total ($counter) is below your last saved total ($saved).", NoticeTone.WARNING)
        PrimaryButton("Correct it first", enabled = !busy, onClick = onCorrectFirst)
        TextLink("Back", onClick = onBack)
        return
    }
    Question(if (singleSpot) "Set the number, then answer three quick questions." else "Almost done. Three quick questions.")

    Question(roomLabel?.let { "Did you count the whole $it?" } ?: "Did you count the whole room?")
    Chips(HeadcountSweepEvent.COVERAGES, coverage, ::coverageLabel, onCoverage)
    AnimatedVisibility(coverage != HeadcountSweepEvent.COVERAGE_COMPLETE) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chips(PART_CHOICES, reason.takeIf { it in PART_CHOICES }, { it }, onReason)
            ReasonField(
                reason,
                onChange = onReason,
                label = if (coverage == HeadcountSweepEvent.COVERAGE_PARTIAL) "Which part?" else "Why not sure?",
            )
        }
    }

    Question("Did people come or go?")
    Chips(HeadcountSweepEvent.STABILITIES, stability, ::stabilityLabel, onStability)

    Question("What were people doing?")
    Chips(HeadcountSweepEvent.ACTIVITIES, activity, ::activityLabel, onActivity)

    Question("Could you have counted someone twice?")
    Chips(HeadcountSweepEvent.DUPLICATE_RISKS, duplicateRisk, ::duplicateLabel, onDuplicateRisk)

    ObserversPicker(observers, onChange = onObservers)

    PrimaryButton(
        "Save $counter ${if (counter == 1) "person" else "people"}",
        enabled = !busy && (coverage == HeadcountSweepEvent.COVERAGE_COMPLETE || reason.isNotBlank()),
        onClick = onSave,
    )
    TextLink(if (singleSpot) "Walk the room instead" else "Back to counting", onClick = onBack)
}

private enum class SweepMode { COUNTING, CORRECTING, FINISHING, ABORTING }

/** The wire value stays `complete | partial | unknown`; the screen says what each means. */
private fun coverageLabel(coverage: String): String = when (coverage) {
    HeadcountSweepEvent.COVERAGE_COMPLETE -> "Whole room"
    HeadcountSweepEvent.COVERAGE_PARTIAL -> "Part of it"
    HeadcountSweepEvent.COVERAGE_UNKNOWN -> "Not sure"
    else -> coverage
}

private fun stabilityLabel(value: String): String = when (value) {
    "stable" -> "No"
    "changed" -> "Yes"
    else -> "Not sure"
}

private fun activityLabel(value: String): String = when (value) {
    "seated" -> "Sitting"
    "moving" -> "Moving"
    "mixed" -> "Both"
    else -> "Not sure"
}

private fun duplicateLabel(value: String): String = when (value) {
    "none_observed" -> "No"
    "possible" -> "Maybe"
    else -> "Not sure"
}

/**
 * Quick descriptions of which part of a room was covered. Each tap sets the reason text, which
 * stays editable: the text is what the `coverage_reason` field carries. A chip is a shortcut, not a
 * vocabulary — the contract keeps free text so an unusual layout is not forced into the wrong half.
 */
private val PART_CHOICES = listOf(
    "Left half",
    "Right half",
    "Near the door",
    "Far end",
    "Window side",
    "One row of desks",
)

// ---- the legacy partial view (IP-140), unchanged in contract ----------------------------------

@Composable
private fun LegacyObserveContent(
    config: ObserveConfig,
    sessionRunning: Boolean,
    sessionStarting: Boolean,
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
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            required <= 0 -> ObserveNotice("This step has no counting setup. Tell the organiser.", NoticeTone.ERROR)
            !sessionRunning -> SessionNotice(starting = sessionStarting)
            silent -> ObserveNotice(
                "Your phone is not broadcasting. Keep the app open and the screen on. Your counts are still saved.",
                NoticeTone.WARNING,
            )
        }

        config.prompt.takeIf { it.isNotBlank() }?.let { Question(it) }

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
            Hint(
                when {
                    recorded == 0 -> "Count what you see, then tap Save. About $required spots is good."
                    else -> "$recorded saved" + (lastRecorded?.let { " · last $it" } ?: "") + ". Stop whenever you like."
                },
            )
            PrimaryButton("Save $count", onClick = { record() })
            // Available after the FIRST reading, and then always. `min_readings` is the
            // suggested number of spots, not a bar. The payload's `of_readings` still says what
            // was suggested, so a reader tells a two-reading set from a five-reading one.
            if (recorded >= 1 && !hasCompleted) {
                SecondaryButton("I'm done") {
                    hasCompleted = true
                    onComplete()
                }
            }
        }
    }
}

// ---- shared pieces -------------------------------------------------------------------------------

@Composable
private fun SessionNotice(starting: Boolean) {
    if (starting) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFE2E8FD))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF5B6ECC))
            Text("Getting ready… a few seconds.", fontSize = 14.sp, color = Color(0xFF3730A3))
        }
    } else {
        ObserveNotice(
            "Recording is off, so counts cannot be saved. Tap Retry in the banner at the top.",
            NoticeTone.ERROR,
        )
    }
}

@Composable
private fun Counter(count: Int, onDecrement: () -> Unit, onIncrement: () -> Unit, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CounterButton("−", onDecrement, enabled = enabled && count > 0)
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(tween(180)) { if (up) it else -it } + fadeIn(tween(180))) togetherWith
                    (slideOutVertically(tween(180)) { if (up) -it else it } + fadeOut(tween(120))) using
                    SizeTransform(clip = true)
            },
            label = "counter",
        ) { value ->
            Text(
                text = "$value",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
                modifier = Modifier.widthIn(min = 96.dp),
                textAlign = TextAlign.Center,
            )
        }
        CounterButton("+", onIncrement, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(
    options: List<String>,
    selected: String?,
    label: (String) -> String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option), fontSize = 14.sp) },
            )
        }
    }
}

@Composable
private fun ObserversPicker(value: Int?, onChange: (Int?) -> Unit) {
    Question("How many people are counting here, you included?")
    Chips(
        options = listOf("1", "2", "3", "?"),
        selected = value?.toString() ?: "?",
        label = { if (it == "?") "Not sure" else it },
    ) { onChange(it.toIntOrNull()) }
}

@Composable
private fun Steps(vararg lines: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { i, line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(Color(0xFFE2E8FD)),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5B6ECC)) }
                Text(line, fontSize = 15.sp, color = Color(0xFF0F172A))
            }
        }
    }
}

@Composable
private fun Question(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF0F172A),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = Color(0xFF64748B),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, color: Color = Color(0xFF5B6ECC), onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(10.dp),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(10.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun TextLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text(text, fontSize = 14.sp) }
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

/** A short green tick that pops in once, so a saved count looks saved. */
@Composable
private fun DoneBadge() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.4f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 300f),
        label = "done-badge",
    )
    Box(
        modifier = Modifier.size(56.dp).scale(scale).clip(CircleShape).background(Color(0xFFDCFCE7)),
        contentAlignment = Alignment.Center,
    ) { Text("✓", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A)) }
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

private enum class NoticeTone(val background: Color, val foreground: Color) {
    ERROR(Color(0xFFFEE2E2), Color(0xFF991B1B)),
    WARNING(Color(0xFFFEF3C7), Color(0xFF92400E)),
}

@Composable
private fun ObserveNotice(
    text: String,
    tone: NoticeTone,
    action: String? = null,
    onAction: () -> Unit = {},
    actionEnabled: Boolean = true,
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = tone.background, shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                color = tone.foreground,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            )
            if (action != null) {
                TextButton(onClick = onAction, enabled = actionEnabled) {
                    Text(action, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tone.foreground)
                }
            }
        }
    }
}
