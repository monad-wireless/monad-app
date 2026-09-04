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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.permissions.DeniedAlwaysException
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.camera.CAMERA
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.compose.koinInject
import qrscanner.CameraLens
import qrscanner.QrScanner
import sk.martinvanco.monad.core.presentation.components.PermissionRequiredCard
import sk.martinvanco.monad.facts.data.FactDto
import sk.martinvanco.monad.facts.domain.DwellState
import sk.martinvanco.monad.facts.domain.FactDeck
import sk.martinvanco.monad.facts.presentation.DwellFactPanel
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.lab.domain.SessionMarker
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.data.dto.ProbeConfig
import sk.martinvanco.monad.quests.data.dto.ProbeTarget
import sk.martinvanco.monad.quests.data.dto.TaskConfigParser
import sk.martinvanco.monad.quests.presentation.components.QuestStepCard

/**
 * IP-140 — scan one of a named set of surveyed points, then hold still for a fixed dwell.
 *
 * This is the whole participant mechanic behind both new quests. One target makes a treasure-hunt
 * leg ("go to monad04, scan it, wait"). Many targets make a fingerprint probe that accepts whichever
 * code the participant is standing at, which is what keeps that quest to a single step.
 *
 * **It never touches the radio.** The identity frame is session-scoped and declared once in the
 * quest's `start` step, so it is already on air when this step opens and stays on air after it
 * closes. That is deliberate and it is the point of the design: the walk *between* two probes is
 * the continuous trajectory the fleet's per-node RSSI reconstructs, and a step that switched the
 * frame on and off would silence the radio for exactly that interval. What this step contributes is
 * a `dwell_start` / `dwell_end` bracket punched into that stream at a position somebody surveyed.
 *
 * The camera opens on entry rather than behind a button. A fingerprint probe is meant to cost a
 * participant one action, and an extra tap on every card is the friction that decides whether a
 * cohort completes the protocol or abandons it.
 *
 * **IT NEVER OPENS A SECOND CAMERA, AND THAT IS NOT A PREFERENCE.** When a walk is tracking, the
 * pose tracker's own session owns the rear camera. A `QrScanner` is a second capture session on the
 * same device: the two contend, the OS picks the loser, and on iOS the loser is ARKit. The 2026-08-27
 * survey walk measured it — `pose=stale` two seconds into the step, `pose=dead@0.0Hz` at sixteen,
 * then two silences of 27 s and 47 s in which the handset shipped no telemetry at all, because the
 * main thread was blocked tearing one capture session down while the other relocalised. The step
 * froze exactly where the first card was read.
 *
 * So the code is read from the frames the tracker is already producing
 * ([LabInstrument.seenCard], decoded off the ARKit frame at 2 Hz), and the standalone scanner is
 * used **only where no tracker owns the camera** — which today means Android, whose pose tracker is
 * not implemented and therefore holds nothing. The test is
 * [LabInstrument.posePreviewHandle] being non-null, not a platform string: it asks the running
 * instrument whether something already has the camera, which is the fact that matters.
 *
 * `PoseTrack.seenCard` carries the same warning for the walk console, which hit this first and was
 * fixed for it. This step reintroduced the fault for participants.
 *
 * Refuses to run without a session, on the same grounds as `BleAdvertiseStep`: a dwell recorded
 * while nothing is recording is thirty seconds of somebody's time spent on nothing, and it must say
 * so rather than complete.
 *
 * @param preScannedValue a code already scanned outside the step — a QR deep link that opened the
 *   app straight into this quest. Non-null means the participant has already pointed a camera at
 *   the thing, so asking them to do it again would be the friction this step exists to remove.
 */
@Composable
fun ProbeStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: (stepData: String?) -> Unit,
    modifier: Modifier = Modifier,
    preScannedValue: String? = null,
    instrument: LabInstrument = koinInject(),
    factDeck: FactDeck = koinInject(),
) {
    val permFactory = rememberPermissionsControllerFactory()
    val permController = remember(permFactory) { permFactory.createPermissionsController() }
    BindEffect(permController)

    var permissionGranted by remember { mutableStateOf(false) }
    var permissionDeniedPermanently by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(permController) {
        permissionGranted = permController.isPermissionGranted(Permission.CAMERA)
    }

    if (!permissionGranted) {
        PermissionRequiredCard(
            permissionName = "Camera",
            deniedPermanently = permissionDeniedPermanently,
            onRequestPermission = {
                scope.launch {
                    try {
                        permController.providePermission(Permission.CAMERA)
                        permissionGranted = true
                        permissionDeniedPermanently = false
                    } catch (e: DeniedAlwaysException) {
                        permissionDeniedPermanently = true
                    } catch (e: DeniedException) {
                        // Denied once; stay on the card.
                    } catch (e: Exception) {
                        permissionDeniedPermanently = false
                    }
                }
            },
            onOpenSettings = { permController.openAppSettings() },
        )
        return
    }

    val config = remember(task) { TaskConfigParser.getProbeConfig(task) }
    val instrumentState by instrument.state.collectAsState()
    val broadcasting by instrument.isBroadcasting.collectAsState(initial = false)

    // Does something already own the rear camera? Re-asked whenever the session starts or stops,
    // because that is the only thing that changes the answer. Non-null means the tracker is running
    // its own capture session and this step must not open another one.
    val trackerPreview = remember(instrumentState.isRunning) { instrument.posePreviewHandle() }
    val trackerOwnsCamera = trackerPreview != null
    val seenCard by instrument.seenCard.collectAsState()

    var matched by remember { mutableStateOf<ProbeTarget?>(null) }
    var remainingSeconds by remember { mutableStateOf(config?.dwellSeconds ?: 0) }
    // The dwell's reading matter (IP-146). Loaded on step entry rather than on the scan, so the
    // panel is on screen the instant the countdown starts. Off the main thread, and a failure to
    // load leaves the list empty and the panel absent — never an exception inside a recording step.
    var factOrder by remember { mutableStateOf<List<FactDto>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Latches for the step's lifetime. The scanner fires its callback repeatedly while a code is in
    // frame, so without these one physical scan opens several dwells.
    var hasScanned by remember { mutableStateOf(false) }
    var hasCompleted by remember { mutableStateOf(false) }

    fun accept(target: ProbeTarget) {
        if (hasScanned) return
        hasScanned = true
        matched = target
        errorMessage = null
        remainingSeconds = config?.dwellSeconds ?: 0
    }

    LaunchedEffect(config) {
        val dwell = config?.dwellSeconds ?: return@LaunchedEffect
        // Two panels more than the countdown can reach. The extra pair is not padding: the panel
        // advances on tap as well as on its timer, and a participant who taps through the lot then
        // stares at a repeat has been told the deck is shorter than it is.
        val panels = (dwell / DWELL_PANEL_SECONDS) + 2
        // IP-151: one panel about the participant, second in the order, rendered from live state.
        factOrder = factDeck.runningOrder(factDeck.all(), panels.coerceAtLeast(3), metaStep = stepNumber)
    }

    // A deep link already delivered the code. Satisfy the step from it rather than asking for a
    // second scan of the same sticker.
    LaunchedEffect(preScannedValue, config) {
        val pre = preScannedValue ?: return@LaunchedEffect
        val target = config?.match(pre) ?: return@LaunchedEffect
        accept(target)
    }

    // The tracker's own decode. Runs only when the tracker owns the camera, which is the same
    // condition under which this step declines to open one of its own.
    //
    // HELD IN VIEW, NOT GLIMPSED. `seenCard` reads whatever is in the tracking camera's frame for
    // the whole walk, not just while somebody is aiming, so a card passed at two metres would
    // otherwise open a dwell at a position nobody stood at — and a fingerprint labelled with the
    // wrong point is worse than no fingerprint. Requiring the same code to survive one further
    // decode (the poll is 500 ms) costs a participant who is standing at a card nothing, and drops
    // one that swept through the frame. `seenCard` is a StateFlow and conflates repeats, so this
    // effect is cancelled and relaunched exactly when the code CHANGES, which is what makes the
    // re-read a real second observation rather than the same one twice.
    LaunchedEffect(seenCard, config, trackerOwnsCamera) {
        if (!trackerOwnsCamera || hasScanned) return@LaunchedEffect
        val code = seenCard ?: return@LaunchedEffect
        val cfg = config ?: return@LaunchedEffect
        delay(600)
        if (instrument.seenCard.value != code) return@LaunchedEffect
        val target = cfg.match(code)
        if (target != null) accept(target) else errorMessage = wrongCodeMessage(cfg)
    }

    // The dwell. Bracketed with markers so the analysis can select the still window without
    // re-deriving it from a countdown it cannot see.
    LaunchedEffect(matched) {
        val target = matched ?: return@LaunchedEffect
        val total = config?.dwellSeconds ?: return@LaunchedEffect

        instrument.markWaypoint(
            code = target.value,
            annotation = target.label.ifBlank { null },
            room = target.room.ifBlank { null },
            targetKind = target.kind.ifBlank { null },
        )
        instrument.mark(
            kind = SessionMarker.Kind.DWELL_START,
            label = target.label.ifBlank { target.value },
            stepId = target.value,
        )

        while (isActive && remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }

        if (remainingSeconds == 0 && !hasCompleted) {
            hasCompleted = true
            instrument.mark(
                kind = SessionMarker.Kind.DWELL_END,
                label = target.label.ifBlank { target.value },
                stepId = target.value,
                payload = "{\"dwell_seconds\":$total}",
            )
            delay(300)
            // The point this dwell happened at, reported as the participant's own observation —
            // the string they scanned, not a key derived from it. The backend owns that
            // extraction (it prints the codes), and duplicating the grammar here would give the
            // lab two copies of it to keep in step. Without this call the dwell is a measurement
            // nobody can place, and the profile's coverage plan stays empty forever.
            onComplete(stepObservation(target, total))
        }
    }

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        // A probe can carry a plan too — the same generator that marks the next stop can mark
        // the one being stood at. Null when it does not, which is the common case.
        imageUrl = TaskConfigParser.stepImageUrl(task),
        content = {
            ProbeContent(
                stepNumber = stepNumber,
                config = config,
                sessionRunning = instrumentState.isRunning,
                broadcasting = broadcasting,
                matched = matched,
                remainingSeconds = remainingSeconds,
                errorMessage = errorMessage,
                facts = factOrder,
                trackerPreview = trackerPreview,
                trackerOwnsCamera = trackerOwnsCamera,
                seenCard = seenCard,
                onScan = { scanned ->
                    if (hasScanned) return@ProbeContent
                    val cfg = config ?: return@ProbeContent
                    val target = cfg.match(scanned)
                    if (target != null) {
                        accept(target)
                    } else {
                        errorMessage = wrongCodeMessage(cfg)
                    }
                },
            )
        },
    )
}

/** What to say when a scan matched nothing, phrased by how many things could have matched. */
private fun wrongCodeMessage(config: ProbeConfig): String = when (config.targets.size) {
    1 -> "That is not the one. Look for ${config.targets.first().label.ifBlank { "the marked point" }}."
    else -> "That code is not part of this run. Find a marked point or a node sticker."
}

@Composable
private fun ProbeContent(
    stepNumber: Int,
    config: ProbeConfig?,
    sessionRunning: Boolean,
    broadcasting: Boolean,
    matched: ProbeTarget?,
    remainingSeconds: Int,
    errorMessage: String?,
    facts: List<FactDto>,
    trackerPreview: Any?,
    trackerOwnsCamera: Boolean,
    seenCard: String?,
    onScan: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            config == null || config.targets.isEmpty() -> ProbeNotice(
                text = "This step has nothing to scan for. It cannot run — tell the operator.",
                background = Color(0xFFFEE2E2),
                foreground = Color(0xFF991B1B),
            )

            !sessionRunning -> ProbeNotice(
                text = "The measurement session is not running, so nothing would record this " +
                    "point. Retry the instrument from the warning banner, or tell the operator.",
                background = Color(0xFFFEE2E2),
                foreground = Color(0xFF991B1B),
            )

            // A dwell with the frame off is a participant standing still for no reason. The quest
            // asked for a broadcast or it did not, and either way the person deserves to know.
            matched != null && !broadcasting -> ProbeNotice(
                text = "Your phone is NOT on air, so the receivers cannot hear you standing here. " +
                    "Keep the app open, and tell the operator if this does not clear.",
                background = Color(0xFFFEF3C7),
                foreground = Color(0xFF92400E),
            )

            matched != null -> ProbeNotice(
                text = "Hold still. Keep the app open and the screen on.",
                background = Color(0xFFFEF3C7),
                foreground = Color(0xFF92400E),
            )
        }

        errorMessage?.let {
            ProbeNotice(
                text = it,
                background = Color(0xFFFEE2E2),
                foreground = Color(0xFF991B1B),
            )
        }

        if (matched == null) {
            // One target names what to look for. Many mean "whatever you are standing at", and
            // listing twenty codes would be noise, not help.
            config?.takeIf { it.targets.size == 1 }?.targets?.first()?.let { target ->
                Text(
                    text = buildString {
                        append(target.label.ifBlank { target.value })
                        if (target.room.isNotBlank()) append(" · ${target.room}")
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A),
                )
            }

            if (config != null && config.targets.isNotEmpty() && sessionRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (trackerOwnsCamera) Modifier else Modifier.height(300.dp))
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    if (trackerOwnsCamera) {
                        // DELIBERATELY NO VIEWFINDER. An ARSCNView here would render the tracker's
                        // session at 60 fps through a UIKit interop, and it would be mounted and
                        // unmounted at exactly the instant this step is most fragile — the read.
                        // That is the shape of the fault this step already had once. The camera is
                        // pointed wherever the phone is pointed, and the line below says what it can
                        // read, twice a second, which is the information a participant actually
                        // needs. If a viewfinder is ever wanted here, it is a measurement, not a
                        // guess: put it back and watch the main thread.
                        ProbeAimHint(seenCard)
                    } else {
                        QrScanner(
                            modifier = Modifier.fillMaxSize(),
                            flashlightOn = false,
                            cameraLens = CameraLens.Back,
                            openImagePicker = false,
                            onCompletion = onScan,
                            imagePickerHandler = { /* Not used */ },
                            onFailure = { /* Surfaced by the notices above, not per frame. */ },
                        )
                    }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (broadcasting) Color(0xFF22C55E) else Color(0xFFEF4444)),
                )
                Text(
                    text = matched.label.ifBlank { matched.value },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A),
                )
                if (matched.room.isNotBlank()) {
                    Text(
                        text = "· ${matched.room}",
                        fontSize = 13.sp,
                        color = Color(0xFF64748B),
                    )
                }
            }

            val total = config?.dwellSeconds ?: 0
            LinearProgressIndicator(
                progress = {
                    if (total == 0) 0f else (total - remainingSeconds).toFloat() / total.toFloat()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF5B6ECC),
                trackColor = Color(0xFFE2E8F0),
            )

            // The countdown, demoted (IP-146). It used to be 72 sp and alone on the screen, which
            // made the number the whole experience of standing still — and a watched number runs
            // slowly. It is now a row: seconds on the left, what they are buying on the right, and
            // the reading matter below carries the interval.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "$remainingSeconds",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "seconds of you standing still",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF334155),
                    )
                    // No node count here. How many receivers are armed is a property of the
                    // night, not of the app, and the app cannot see it — a printed "nine" would
                    // be wrong on any night six were up.
                    Text(
                        text = "The receivers are recording this window. Read something while " +
                            "they do.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = Color(0xFF64748B),
                    )
                }
            }

            DwellFactPanel(
                facts = facts,
                panelSeconds = DWELL_PANEL_SECONDS,
                dwellState = DwellState(
                    stepNumber = stepNumber,
                    dwellSeconds = total,
                    heldSeconds = (total - remainingSeconds).coerceAtLeast(0),
                    panelsShown = 0,
                ),
            )
        }
    }
}

/**
 * What a completed dwell reports about itself.
 *
 * `targets` is the list the backend reads to build a participant's coverage plan, and it is a list
 * rather than a scalar because a probe step MAY carry several legs in a later quest shape — the
 * field is the backend's and its plural is not this step's to narrow. The dwell length travels
 * with it so a reduction can tell a thirty-second window from a sixty-second one without going
 * back to the quest spec, which may have been re-generated since.
 */
private fun stepObservation(target: ProbeTarget, dwellSeconds: Int): String =
    Json.encodeToString(
        JsonObject(
            mapOf(
                "targets" to JsonArray(listOf(JsonPrimitive(target.value))),
                "dwell_seconds" to JsonPrimitive(dwellSeconds),
                "label" to JsonPrimitive(target.label),
                "room" to JsonPrimitive(target.room),
                "kind" to JsonPrimitive(target.kind),
            )
        )
    )

/**
 * How long one fact stays up.
 *
 * Chosen against the export's own bound rather than by taste: `deck_facts.py` caps a body at 420
 * characters, and adult silent reading runs at roughly 240 words a minute, so a full-length panel
 * is about seventy words and takes about eighteen seconds to read carefully. Eleven seconds is
 * deliberately shorter than that — the panel advances on tap, and somebody still reading taps
 * nothing, whereas somebody who has finished should not have to wait. A thirty-second dwell
 * therefore shows two facts on the timer and as many more as the participant asks for.
 */
private const val DWELL_PANEL_SECONDS = 11

/**
 * What the tracking camera can read right now.
 *
 * The only feedback a participant gets while aiming, because this step mounts no viewfinder — see
 * the note at the call site. It is a CONDITION, refreshed at the tracker's 2 Hz decode, so "nothing
 * yet" and "reading the wrong thing" are distinguishable without a tap.
 */
@Composable
private fun ProbeAimHint(seenCard: String?) {
    Text(
        text = seenCard?.let { "Reading $it \u2014 hold it there" }
            ?: "Point the phone at a code on a card or a grey box, and hold it steady.",
        fontSize = 13.sp,
        color = Color(0xFF64748B),
    )
}

@Composable
private fun ProbeNotice(text: String, background: Color, foreground: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = foreground,
            lineHeight = 18.sp,
            modifier = Modifier.padding(12.dp),
        )
    }
}
