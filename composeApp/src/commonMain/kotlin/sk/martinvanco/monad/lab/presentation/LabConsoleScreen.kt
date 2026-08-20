package sk.martinvanco.monad.lab.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.permissions.DeniedAlwaysException
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.camera.CAMERA
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import qrscanner.CameraLens
import qrscanner.QrScanner
import sk.martinvanco.monad.core.presentation.components.PermissionRequiredCard
import sk.martinvanco.monad.core.presentation.components.ScreenWithBackNavigation
import sk.martinvanco.monad.lab.domain.TrackingQuality
import sk.martinvanco.monad.lab.domain.health.LabStream
import sk.martinvanco.monad.lab.domain.preflight.PreflightSeverity

/**
 * The lab console — the operator surface for a **measurement walk**.
 *
 * Read top to bottom it is one question per panel, in the order they have to be answered:
 *
 *  1. **walk** — is this thing recording, and is what it is recording any good?
 *  2. **ready** — will it work before you start, and what is missing if not.
 *  3. **on air** — can the fleet hear this phone, and does backgrounding kill that.
 *  4. **track** — is there a trajectory, and does its length match the corridor you walked.
 *  5. **waypoints** — the surveyed points that turn the trajectory into a place.
 *  6. **sessions / log** — what is on the phone, and what the instrument has said.
 *
 * Layout is dense and tabular rather than card-pretty for the original console's reason, unchanged:
 * this is read at arm's length while walking, or on a bench next to a Raspberry Pi while something is
 * not working. Six panels, not thirteen. What went and why is in [LabConsoleState].
 */
class LabConsoleScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<LabConsoleScreenModel>()
        val state by model.state.collectAsState()

        ScreenWithBackNavigation(title = "Lab console", onBackClick = { navigator.pop() }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Two consoles, one screen. While a walk runs the operator is standing in a room
                // holding a phone: they get the panels a walk is steered by and nothing else.
                // Idle, they get the setup and housekeeping panels — which are exactly the ones
                // that read as "mostly empty" mid-walk, because mid-walk they are.
                if (state.isRunning) {
                    WalkPanel(state, model)
                    WaypointPanel(state, model)
                    TrackPanel(state, model)
                    MeshPanel(state)
                    BroadcastPanel(state, model)
                    LogPanel(state, model, limit = RUNNING_LOG_LINES)
                } else {
                    WalkPanel(state, model)
                    ReadinessPanel(state, model)
                    BroadcastPanel(state, model)
                    TrackPanel(state, model)
                    SessionsPanel(state, model)
                    LogPanel(state, model, limit = IDLE_LOG_LINES)
                }
                state.message?.let {
                    Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // The stop gate. Dismissable, not a lock: the warning names what this close silently
        // costs, and the operator decides — while still standing where fixing it is cheap.
        state.stopWarning?.let { warning ->
            AlertDialog(
                onDismissRequest = { model.onEvent(LabConsoleEvent.DismissStopWarning) },
                title = { Text("Stop with holes?") },
                text = { Text(warning) },
                confirmButton = {
                    TextButton(onClick = { model.onEvent(LabConsoleEvent.ConfirmStopSession) }) {
                        Text("Stop anyway")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { model.onEvent(LabConsoleEvent.DismissStopWarning) }) {
                        Text("Keep walking")
                    }
                },
            )
        }
    }
}

// ---- chrome ---------------------------------------------------------------------------------

@Composable
private fun Panel(title: String, content: @Composable ColumnScopeAlias.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp))) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title.uppercase(), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            HorizontalDivider()
            content()
        }
    }
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun KeyValue(key: String, value: String, warn: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(
            value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 10.sp)
}

@Composable
private fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(detail, fontSize = 9.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

// ---- 1. walk --------------------------------------------------------------------------------

/**
 * The panel the operator reads while walking, so it says one sentence in large type.
 *
 * The three toggles above the button are the whole session request. They are here rather than in a
 * settings screen because "what is this walk recording" is a per-walk decision, and a walk with
 * tracking silently left off from last time is a walk with no ground truth.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalkPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("walk") {
    Text(
        state.walkHeadline,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = if (!state.isRunning) {
            MaterialTheme.colorScheme.onSurface
        } else if (state.walkHealthy) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    // The coaching sentence: what to change with your hands, right now. Above everything else
    // because it is the only line on this screen that can improve the walk while it runs.
    state.coaching?.let {
        Text(
            it,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
    }

    // What the tracking camera sees. The operator reading this console is why walk A's camera
    // stared at carpet; a preview held so it shows the room ahead is the fix, made visible.
    if (state.isRunning && state.trackEnabled && state.poseReport != null) {
        if (state.showCameraPreview) {
            WalkCameraPreview(
                handle = model.previewHandle(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        ToggleRow(
            label = "camera preview",
            detail = "what the tracker sees — hold the phone so this shows the room ahead",
            checked = state.showCameraPreview,
            enabled = true,
            onChange = { model.onEvent(LabConsoleEvent.ToggleCameraPreview(it)) },
        )
    }

    if (state.isRunning) {
        KeyValue("elapsed", formatElapsed(state.elapsedMillis))
        KeyValue("session", state.instrument.sessionId?.take(8) ?: "-")
        // The link that makes everything else joinable. A walk with no clock sample is a trajectory and
        // a mesh on a device-local timeline nobody else shares — recorded, and unusable.
        KeyValue(
            "clock",
            if (state.clockSynced) {
                "±${format(state.clock.delayMillis / 2)} ms  ${state.clock.samples} sample(s)"
            } else {
                "NOT SYNCED"
            },
            warn = !state.clockSynced,
        )
    } else {
        KeyValue("bundle", "v${state.config.version} (${state.configSource.name.lowercase()})")
        KeyValue("site", state.config.site.ifBlank { "unset" })
    }

    // Setup is an idle activity. Mid-walk these rows are all disabled — six lines of grey that
    // push the waypoint panel below the fold on the screen an operator reads at arm's length.
    if (!state.isRunning) {
        HorizontalDivider()

        ToggleRow(
            label = "advertise identity",
            detail = "the frame the fleet's BLE scan hears — without it nothing it records is yours",
            checked = state.broadcastEnabled,
            enabled = true,
            onChange = { model.onEvent(LabConsoleEvent.ToggleBroadcast(it)) },
        )
        ToggleRow(
            label = "record trajectory",
            detail = "where the phone was, at ${state.trackRateHz.toInt()} Hz",
            checked = state.trackEnabled,
            enabled = true,
            onChange = { model.onEvent(LabConsoleEvent.ToggleTrack(it)) },
        )
        ToggleRow(
            label = "witness anchors",
            detail = if (state.witnessAvailable) {
                "monitor the ESP32 iBeacons in ${state.config.beacons.zones.size} zone(s)"
            } else {
                "no surveyed anchors in this bundle — a walk does not need them, the fleet hears the phone"
            },
            checked = state.witnessEnabled,
            enabled = state.witnessAvailable,
            onChange = { model.onEvent(LabConsoleEvent.ToggleWitness(it)) },
        )
    }

    if (state.trackEnabled && !state.isRunning) {
        ToggleRow(
            label = "use site map",
            detail = "relocalise into the site's saved frame — turn OFF for a bench test away " +
                "from the mapped room (a wrong map costs the walk a 25 s fresh-origin fallback)",
            checked = state.useSiteMap,
            enabled = true,
            onChange = { model.onEvent(LabConsoleEvent.ToggleSiteMap(it)) },
        )
        Text("pose rate", fontSize = 10.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabConsoleState.TRACK_RATE_OPTIONS.forEach { rate ->
                FilterChip(
                    selected = state.trackRateHz == rate,
                    onClick = { model.onEvent(LabConsoleEvent.SelectTrackRate(rate)) },
                    label = { Text("${rate.toInt()} Hz", fontSize = 10.sp) },
                )
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.isRunning) {
            Button(
                onClick = { model.onEvent(LabConsoleEvent.StopSession) },
                enabled = !state.isBusy,
            ) { Text("Stop walk") }
        } else {
            Button(
                onClick = { model.onEvent(LabConsoleEvent.StartSession) },
                enabled = !state.isBusy,
            ) { Text("Start walk") }
        }
        OutlinedButton(
            onClick = { model.onEvent(LabConsoleEvent.RefreshConfig) },
            enabled = !state.isBusy,
        ) { Text("Bundle") }
    }
    if (!state.isRunning) {
        Note(
            "Keep the app on screen for the whole walk. iOS takes the identity frame off the air " +
                "the fleet can read the moment the app is backgrounded, and ARKit pauses with it — " +
                "neither reports an error when it happens."
        )
    }
}

// ---- 2. readiness ---------------------------------------------------------------------------

/**
 * Pre-flight and background residency, together.
 *
 * One panel, because they answer one question and the operator acts on them in the same breath. The
 * pre-flight is judged **as a walk** — see `Preflight.evaluate` — so the collector, the access point
 * and the clock gate are not asked about. Before that was true this panel reported three permanent
 * blockers on every walk, which trained the operator to ignore it.
 */
@Composable
private fun ReadinessPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("ready") {
    val report = state.preflight
    if (report == null) {
        Note("Not run. Answers what is missing before you are standing in the room.")
    } else {
        Text(
            report.headline,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (report.isGo) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error,
        )
        report.checks.forEach { check ->
            val bad = check.severity != PreflightSeverity.PASS
            KeyValue("${check.severity.name}  ${check.id.title}", check.detail, warn = bad)
            if (bad) check.remedy?.let {
                Text(
                    "    $it",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    state.residency.forEach { KeyValue(it.name, it.detail, warn = !it.satisfied) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { model.onEvent(LabConsoleEvent.RunPreflight) },
            enabled = !state.preflightRunning,
        ) { Text(if (state.preflightRunning) "Checking…" else "Check") }
        if (state.residencyBlockers.isNotEmpty()) {
            OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.RequestPrerequisites) }) {
                Text("Grant")
            }
        }
    }
}

// ---- 3. on air ------------------------------------------------------------------------------

/**
 * What the fleet can actually hear.
 *
 * Shows the **accepted** values, never the commanded ones — the interval Android rounded to, or iOS's
 * "system-controlled" — because the fleet-side join reads what went on air. A panel showing 250 ms
 * because 250 ms was asked for would be a fabricated radio configuration in an operator's face.
 */
@Composable
private fun BroadcastPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("on air") {
    KeyValue(
        "state",
        if (state.isBroadcasting) "BROADCASTING" else "silent",
        warn = state.isRunning && state.broadcastEnabled && !state.isBroadcasting,
    )
    val report = state.broadcastReport
    if (report != null) {
        KeyValue("service uuid", report.serviceUuid)
        KeyValue("interval", report.acceptedInterval)
        KeyValue("tx power", report.txPower)
        if (report.foregroundOnly) {
            KeyValue("posture", "FOREGROUND ONLY", warn = true)
            Note(
                "Backgrounded, this platform moves the service UUID into an overflow area a raw HCI " +
                    "scanner cannot parse. The phone still reports itself as advertising."
            )
        }
        report.notes.forEach { Note(it) }
    } else {
        KeyValue("namespace", state.advertiseNamespace ?: "UNSET", warn = state.advertiseNamespace == null)
    }
    state.broadcastDiagnostics.forEach { Note(it) }

    if (state.isRunning) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isBroadcasting) {
                OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.StopBroadcast) }) {
                    Text("Go silent")
                }
            } else {
                OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.StartBroadcast) }) {
                    Text("Go on air")
                }
            }
        }
        Note(
            "Going silent mid-walk writes a broadcast_stop marker. That is how one recording carries " +
                "a silent arm against the same trajectory and the same room."
        )
    } else {
        OutlinedTextField(
            value = state.manualAdvertiseNamespace,
            onValueChange = { model.onEvent(LabConsoleEvent.UpdateAdvertiseNamespace(it)) },
            label = { Text("advertise namespace", fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.ApplyAdvertiseNamespace) }) {
            Text("Apply locally")
        }
        Note("Keep the last four bytes zero — the phone writes the participant and session keys there.")
    }
}

// ---- 4. track -------------------------------------------------------------------------------

/**
 * The trajectory, while it is still free to fix.
 *
 * **Path length is the line to read.** Look at the corridor, look at the number: a forty-metre walk
 * that came back as four metres is a tracker that never initialised, and no other single value on the
 * phone catches that. Tracking quality is second, because odometry that has lost itself keeps
 * returning plausible positions and never says so.
 */
@Composable
private fun TrackPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("track") {
    val progress = state.poseProgress
    val report = state.poseReport

    if (report == null && !state.isRunning) {
        KeyValue(
            "tracker",
            if (state.trackerAvailable) "available" else "UNAVAILABLE",
            warn = !state.trackerAvailable,
        )
        state.trackerDiagnostics.forEach { Note(it) }
        return@Panel
    }

    KeyValue(
        "quality",
        progress.quality.wire.uppercase(),
        warn = progress.quality != TrackingQuality.NORMAL,
    )
    progress.last?.reason?.let { KeyValue("reason", it, warn = true) }
    KeyValue("walked", "${format(progress.pathLengthMetres)} m")
    // Beside the distance, always. A rising count is the live signal that the tracker is
    // re-solving its world rather than following the body — actionable in the room, and the
    // only thing that explains a distance that looks wrong.
    KeyValue(
        "jumps rejected",
        progress.rejectedJumps.toString(),
        warn = progress.rejectedJumps > 0 && progress.samples > 0 &&
            progress.rejectedJumps.toDouble() / progress.samples > 0.01,
    )
    KeyValue("poses", progress.samples.toString())
    progress.normalFraction?.let {
        KeyValue("trusted", "${(it * 100).toInt()} %", warn = it < 0.8)
    }
    // The variable that separated the two 2026-08-19 walks. −90 is the floor, 0 the horizon.
    progress.pitchDegrees?.let {
        KeyValue("camera pitch", "${it.toInt()}°", warn = it < LabConsoleState.PITCH_FLOOR_DEGREES)
    }
    progress.last?.let {
        KeyValue("position", "x ${format(it.x.toDouble())}  z ${format(it.z.toDouble())} m")
    }
    if (report != null) {
        KeyValue("depth", if (report.depthAssisted) "LiDAR assisted" else "camera + IMU only")
        KeyValue("commanded", "${report.commandedRateHz.toInt()} Hz")
        KeyValue("frame", report.worldAlignment)
    }
    // Delivered pace, from the health monitor. The commanded rate is a request; a phone that thermally
    // throttles delivers fewer poses and this is where that shows up as a number rather than as a
    // slightly sparser file nobody opens.
    state.health.streams
        .firstOrNull { it.stream == LabStream.POSE }
        ?.let { stream ->
            KeyValue("stream", stream.state.wire, warn = !stream.state.isHealthy)
            stream.deliveredFraction?.let {
                KeyValue("delivered", "${(it * 100).toInt()} % of commanded", warn = it < 0.5)
            }
        }
    if (progress.rejectedJumps > 5) {
        Note(
            "The tracker is re-solving its world repeatedly. Hold the phone up and forward with a " +
                "clear view several metres ahead — pointing at a blank wall or down at the floor " +
                "starves it of parallax. The distance above already excludes these jumps."
        )
    }
    Note(
        "The origin is wherever tracking started and the axes are gravity-aligned. These are metres " +
            "in that frame, not coordinates in the building — waypoints are what convert one to the " +
            "other."
    )
}

// ---- 4b. mesh -------------------------------------------------------------------------------

/**
 * The room, as it is discovered.
 *
 * The number to watch is **faces against time**. A mesh that stops growing halfway through a walk is
 * LiDAR looking at something it cannot resolve — a window, a dark corridor, a surface past its range —
 * and the fix is to walk that stretch again, more slowly. After the session it is a hole in the geometry
 * with no explanation, so this panel exists to make it a decision rather than a discovery.
 *
 * `classified` is not cosmetic either: the ray-traced channel simulator wants materials rather than
 * surfaces, and an unlabelled mesh means every wall and every seat gets the same permittivity.
 */
@Composable
private fun MeshPanel(state: LabConsoleState) = Panel("mesh") {
    if (!state.trackEnabled) {
        Note("Geometry comes with the trajectory. Switch on \"record trajectory\" to scan the room.")
        return@Panel
    }
    val depth = state.poseReport?.depthAssisted
    if (depth == false) {
        Note(
            "No LiDAR on this device, so no geometry. The trajectory and the waypoints are unaffected — " +
                "tracking is camera and IMU only and drifts further."
        )
        return@Panel
    }

    KeyValue("blocks", state.mesh.anchors.toString(), warn = state.isRunning && !state.mesh.hasGeometry)
    KeyValue("faces", state.mesh.faces.toString())
    KeyValue("vertices", state.mesh.vertices.toString())
    KeyValue("changes logged", state.mesh.revisions.toString())
    if (state.mesh.hasGeometry) {
        KeyValue(
            "labels",
            if (state.mesh.classified) "wall/floor/seat/…" else "none",
            warn = !state.mesh.classified,
        )
    }
    if (state.isRunning && !state.mesh.hasGeometry) {
        Note(
            "Nothing scanned yet. Scene reconstruction needs a few seconds and a surface within a few " +
                "metres — point the phone at a wall rather than down a corridor."
        )
    }
    Note(
        "Exported at stop as mesh.ply, in the same frame as the trajectory. mesh.tsv records when each " +
            "block became what the file contains, on the same clock as everything else."
    )
}

// ---- 5. waypoints ---------------------------------------------------------------------------

/**
 * The surveyed points that turn a shape into a place.
 *
 * Three or more of them over a walk determine the transform from the session-local frame into the
 * site's, **and** bound the drift that accumulated between them. Fewer than three and the walk has a
 * trajectory whose position in the building is unknown.
 *
 * No camera while the tracker runs, and that is a hardware fact rather than a preference: ARKit holds
 * the capture session for the whole walk, and a QR scanner beside it interrupts one of the two. The
 * loser is the OS's choice, so scanning could put a hole in the trajectory at the exact instant the
 * waypoint is meant to anchor it.
 */
@Composable
private fun WaypointPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("waypoints") {
    if (!state.isRunning) {
        Note(
            "Start a walk first. A waypoint outside a session has no timeline to sit on, so it is " +
                "refused rather than queued."
        )
        return@Panel
    }

    KeyValue("recorded", state.waypoints.size.toString(), warn = state.waypoints.size < 3)
    if (state.waypoints.size < 3) {
        Note(
            "Under three waypoints the trajectory has a shape and no place: the transform into the " +
                "building's frame is not determined and the drift between fixes is unbounded."
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = {
                model.onEvent(LabConsoleEvent.SelectWaypointPoint(state.waypointPoint - 1))
            },
            enabled = state.waypointPoint > 1,
        ) { Text("−") }
        Text(
            LabConsoleState.fingerprintCode(state.waypointPoint),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = {
                model.onEvent(LabConsoleEvent.SelectWaypointPoint(state.waypointPoint + 1))
            },
            enabled = state.waypointPoint < LabConsoleState.FINGERPRINT_CARD_COUNT,
        ) { Text("+") }
    }

    OutlinedTextField(
        value = state.waypointCode,
        onValueChange = { model.onEvent(LabConsoleEvent.UpdateWaypointCode(it)) },
        label = { Text("or type a card code", fontSize = 10.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = { model.onEvent(LabConsoleEvent.MarkWaypoint()) },
        enabled = !state.isBusy && !state.isDwelling,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Record ${state.pendingWaypointCode}") }

    // The stationary probe arm: stand on a card, hold, end. Records the waypoint AND brackets the
    // interval with dwell markers, so the analysis reads the CSI statistic against a fixed position
    // with no direction-of-motion confound — the arm that can settle the walked-vs-simulated sign.
    if (state.isDwelling) {
        Button(
            onClick = { model.onEvent(LabConsoleEvent.EndDwell) },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "End dwell on ${state.dwellCode} " +
                    "(${formatElapsed((state.nowMonotonicNanos - state.dwellStartedMonotonicNanos) / 1_000_000)})"
            )
        }
        Note("Stand still on the card until you end the dwell. Walking during a dwell poisons the arm.")
    } else {
        OutlinedButton(
            onClick = { model.onEvent(LabConsoleEvent.StartDwell) },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Dwell on ${state.pendingWaypointCode}") }
    }

    if (state.isScanning) {
        CameraGate {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                QrScanner(
                    modifier = Modifier.fillMaxSize(),
                    flashlightOn = false,
                    cameraLens = CameraLens.Back,
                    openImagePicker = false,
                    onCompletion = { model.onEvent(LabConsoleEvent.WaypointScanned(it)) },
                    imagePickerHandler = { },
                    // Decode misses fire constantly while the card is being lined up. Surfacing them
                    // would bury the message that matters.
                    onFailure = { Napier.d("[lab] waypoint qr decode miss: $it") },
                )
            }
        }
        OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.StopWaypointScan) }) { Text("Cancel") }
    } else if (state.poseReport == null) {
        OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.StartWaypointScan) }) {
            Text("Scan card")
        }
    } else {
        Note("Scanning is unavailable while the camera is tracking. Tap or type the card code.")
    }

    state.waypoints.take(10).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.code, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(
                    if (row.x != null && row.z != null) {
                        "x ${format(row.x.toDouble())}  z ${format(row.z.toDouble())} m  " +
                            row.quality.wire
                    } else {
                        "no pose"
                    },
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (row.quality == TrackingQuality.NORMAL) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.MarkWaypoint(row.code)) }) {
                Text("again", fontSize = 10.sp)
            }
        }
    }
}

// ---- 6. sessions and log --------------------------------------------------------------------

@Composable
private fun SessionsPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("sessions") {
    KeyValue("unsynced", state.unsyncedCount.toString(), warn = state.unsyncedCount > 0)
    state.lastFlush?.let { KeyValue("last flush", it.headline, warn = !it.isClean) }
    if (state.unsyncedCount > 0) {
        OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.RetryUploads) }) {
            Text("Retry uploads")
        }
    }
    state.sessions.take(12).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${row.sessionId.take(8)}  ${row.status}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (row.status == "uploaded") MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                )
                row.uploadError?.let {
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.DeleteSession(row.sessionId)) }) {
                Text("del", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun LogPanel(state: LabConsoleState, model: LabConsoleScreenModel, limit: Int) = Panel("log") {
    state.log.takeLast(limit).reversed().forEach { line ->
        Text(
            line.message,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(2.dp),
        )
    }
    OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.ClearLog) }) { Text("Clear") }
}

/**
 * Camera permission gate, mirroring the check-in screen and the quest QR step exactly.
 *
 * There is one way this app asks for a camera, and this is a copy of it rather than a shared component
 * because the two screens' surrounding copy differs — a participant needs "we need the camera to read
 * the door code", an operator needs nothing but the button.
 */
@Composable
private fun CameraGate(content: @Composable () -> Unit) {
    val factory = rememberPermissionsControllerFactory()
    val controller = remember(factory) { factory.createPermissionsController() }
    BindEffect(controller)

    var granted by remember { mutableStateOf(false) }
    var deniedPermanently by remember { mutableStateOf(false) }
    var resumeCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(Unit) {
        resumeCount++
        onPauseOrDispose { }
    }

    LaunchedEffect(controller, resumeCount) {
        val isGranted = controller.isPermissionGranted(Permission.CAMERA)
        granted = isGranted
        if (isGranted) deniedPermanently = false
    }

    if (!granted) {
        PermissionRequiredCard(
            permissionName = "Camera",
            deniedPermanently = deniedPermanently,
            onRequestPermission = {
                scope.launch {
                    try {
                        controller.providePermission(Permission.CAMERA)
                        granted = true
                        deniedPermanently = false
                    } catch (e: DeniedAlwaysException) {
                        deniedPermanently = true
                    } catch (e: DeniedException) {
                        // Denied once; the card stays and the operator can try again.
                    } catch (e: Exception) {
                        deniedPermanently = false
                    }
                }
            },
            onOpenSettings = { controller.openAppSettings() },
        )
        return
    }
    content()
}


/**
 * Log lines shown while walking. Eight — the stall/resume chatter of a struggling tracker fills
 * forty lines in a minute, and the panel exists to show the last thing that happened, not to be
 * scrolled mid-walk. The full log persists and uploads regardless.
 */
private const val RUNNING_LOG_LINES = 8

/** Log lines shown idle, where reading back a finished session is the point. */
private const val IDLE_LOG_LINES = 40

/** Three decimals, which is a millimetre — past what odometry resolves and short enough to read. */
private fun format(value: Double): String {
    val scaled = (value * 1000).toLong()
    val whole = scaled / 1000
    val fraction = (if (scaled < 0) -scaled else scaled) % 1000
    return "$whole.${fraction.toString().padStart(3, '0')}"
}
