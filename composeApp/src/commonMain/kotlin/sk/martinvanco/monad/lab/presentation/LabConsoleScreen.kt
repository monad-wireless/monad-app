package sk.martinvanco.monad.lab.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import qrgenerator.qrkitpainter.rememberQrKitPainter
import sk.martinvanco.monad.core.presentation.components.ScreenWithBackNavigation
import sk.martinvanco.monad.lab.domain.BlockKind
import sk.martinvanco.monad.lab.domain.SubCondition
import sk.martinvanco.monad.lab.domain.TallySource
import sk.martinvanco.monad.lab.domain.preflight.PreflightSeverity

/**
 * The lab console.
 *
 * Layout is deliberately dense and tabular rather than card-pretty: this is read on a bench, next
 * to a Raspberry Pi, while something is not working. The order of the panels is the order of the
 * EXP-P3 gates — residency, binding, clock, emission, witnessing, sessions — so reading top to
 * bottom walks the same sequence the instrument does at start-up, and the first red line is the
 * one that matters.
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
                StatusPanel(state)
                PreflightPanel(state, model)
                ResidencyPanel(state, model)
                CollectorPanel(state, model)
                ProfilePanel(state, model)
                ControlPanel(state, model)
                // Directly under the session controls: it is the operator's most frequent action
                // of the day, and everything below it is diagnostics.
                BlockPanel(state, model)
                TrafficPanel(state)
                ClockPanel(state)
                WitnessPanel(state)
                GroundTruthPanel(state, model, onOpenScanner = { navigator.push(GroundTruthScanScreen()) })
                SessionsPanel(state, model)
                LogPanel(state, model)
            }
        }
    }
}

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
private fun StatusPanel(state: LabConsoleState) = Panel("instrument") {
    KeyValue("phase", state.instrument.phase.name.lowercase())
    KeyValue("session", state.instrument.sessionId?.take(8) ?: "—")
    KeyValue("config", "v${state.config.version} ${state.config.site.ifBlank { "(no site)" }} [${state.configSource.name.lowercase()}]")
    state.instrument.lastError?.let { KeyValue("last error", it, warn = true) }
    state.message?.let { KeyValue("message", it) }
}

@Composable
private fun ResidencyPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("residency") {
    state.residency.forEach { check ->
        KeyValue(check.name, if (check.satisfied) "ok" else "MISSING — ${check.detail}", warn = !check.satisfied)
    }
    if (state.residencyBlockers.isNotEmpty()) {
        OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.RequestPrerequisites) }) {
            Text("Request permissions")
        }
    }
}

@Composable
private fun CollectorPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("collector") {
    KeyValue("host", state.config.collector.host.ifBlank { "—" })
    KeyValue("udp port", state.config.collector.udpPort.toString())
    KeyValue(
        "socket",
        if (state.instrument.socketPinned) state.instrument.boundInterface
        else state.instrument.boundInterface.ifBlank { "not open" },
        warn = state.isRunning && !state.instrument.socketPinned,
    )
    OutlinedTextField(
        value = state.manualHost,
        onValueChange = { model.onEvent(LabConsoleEvent.UpdateManualHost(it)) },
        label = { Text("collector IPv4") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.manualPort,
        onValueChange = { model.onEvent(LabConsoleEvent.UpdateManualPort(it)) },
        label = { Text("udp port") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.manualBeaconUuid,
        onValueChange = { model.onEvent(LabConsoleEvent.UpdateManualBeaconUuid(it)) },
        label = { Text("beacon uuid") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.ApplyManualCollector) }) {
            Text("Apply override")
        }
        OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.RefreshConfig) }) {
            Text("Refetch bundle")
        }
    }
}

@Composable
private fun ProfilePanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("roles") {
    Text("access point", fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.config.accessPoints.forEach { ap ->
            FilterChip(
                selected = state.selectedApId == ap.id,
                onClick = { model.onEvent(LabConsoleEvent.SelectAp(ap.id)) },
                label = { Text("${ap.ssid} ${ap.band}", fontSize = 11.sp) },
            )
        }
    }
    Text("traffic profile", fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.config.trafficProfiles.forEach { profile ->
            FilterChip(
                selected = state.selectedProfileId == profile.id,
                onClick = { model.onEvent(LabConsoleEvent.SelectProfile(profile.id)) },
                label = { Text("${profile.rateHz.toInt()} Hz", fontSize = 11.sp) },
            )
        }
    }
    KeyValue("witness", if (state.config.beacons.isConfigured) "${state.config.beacons.zones.size} anchors" else "disabled")
}

@Composable
private fun ControlPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("control") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { model.onEvent(LabConsoleEvent.StartSession) },
            enabled = !state.isRunning && !state.isBusy,
        ) { Text("Start session") }
        OutlinedButton(
            onClick = { model.onEvent(LabConsoleEvent.StopSession) },
            enabled = state.isRunning && !state.isBusy,
        ) { Text("Stop + upload") }
    }
    Text(
        "Start backgrounds the app and leaves it running: put the phone in a pocket, screen off. " +
            "That is the only condition worth measuring.",
        fontSize = 10.sp,
    )
}

/**
 * Block control — the panel this whole pass exists for.
 *
 * Design constraints, all of them learned the expensive way:
 *
 * - **The running block must be unmistakable at arm's length.** An operator glancing at the phone
 *   between plateaus needs to read zone, level, sub-condition and kind without focusing, because the
 *   failure this prevents — a block labelled level 4 that ran at level 6 — cannot be detected in
 *   analysis. The label *is* the ground truth the analysis uses.
 * - **The previous block's duration stays on screen.** The pre-registration's dwell budgets
 *   (175 s / 350 s sub-blocks, 1.5 min plateaus, 0.5 min ramps) are only keepable if the operator
 *   can see what the last one actually cost.
 * - **Warnings are shown, never enforced.** A deliberate off-plan block is sometimes right; an
 *   unknowing one never is.
 */
@Composable
private fun BlockPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("block") {
    val active = state.blocks.active

    if (active != null) {
        val overBudget = state.blockLiveWarnings.isNotEmpty()
        val accent =
            if (overBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Text(
            "RUNNING",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        Text(
            active.headline,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = accent,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                active.kind.designedSeconds?.let { "design ${it} s" } ?: "untimed",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                formatElapsed(state.blockElapsedMillis),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
        }
        state.blockBudgetFraction?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = accent,
            )
        }
        state.blockLiveWarnings.forEach { WarningLine(it.message) }
    } else {
        Text(
            "NO BLOCK RUNNING",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "Everything recorded from here is unlabelled. The analysis would have to infer block " +
                "boundaries from the count trace, which is worst at cycling ramps — the events T3 " +
                "is built on.",
            fontSize = 10.sp,
        )
    }

    state.blocks.last?.let { previous ->
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        KeyValue(
            "previous",
            "${previous.headline}  ${formatElapsed(previous.durationMillis)}",
            warn = !previous.withinBudget,
        )
        KeyValue(
            "  closed",
            previous.stopReason.label,
            warn = previous.stopReason != sk.martinvanco.monad.lab.domain.BlockStopReason.OPERATOR,
        )
    }

    state.blockLastWarnings.forEach { WarningLine(it.message) }

    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

    Text("zone", fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.blockZoneOptions.forEach { zoneId ->
            FilterChip(
                selected = state.blockZoneId == zoneId,
                onClick = { model.onEvent(LabConsoleEvent.SelectBlockZone(zoneId)) },
                label = { Text(zoneId, fontSize = 11.sp) },
            )
        }
    }

    Text("level (frozen staircase)", fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        state.blockLevelOptions.forEach { level ->
            FilterChip(
                selected = state.blockLevel == level,
                onClick = { model.onEvent(LabConsoleEvent.SelectBlockLevel(level)) },
                label = { Text("$level", fontSize = 11.sp) },
            )
        }
    }

    Text("sub-condition", fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SubCondition.entries.forEach { condition ->
            FilterChip(
                selected = state.blockSubCondition == condition,
                onClick = { model.onEvent(LabConsoleEvent.SelectBlockSubCondition(condition)) },
                label = { Text(condition.label, fontSize = 11.sp) },
            )
        }
    }

    Text("kind", fontSize = 11.sp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BlockKind.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { kind ->
                    FilterChip(
                        selected = state.blockKind == kind,
                        onClick = { model.onEvent(LabConsoleEvent.SelectBlockKind(kind)) },
                        label = { Text(kind.label, fontSize = 10.sp) },
                    )
                }
            }
        }
    }

    // The level the operator is about to declare, next to the number of people the room says are
    // in it. Two numbers side by side is the cheapest guard there is against the one error that
    // cannot be repaired afterwards.
    KeyValue(
        "declaring / room says",
        "${state.blockLevel} / ${
            state.blockTally?.let { "${it.count} (${it.source.wire})" } ?: "no tally"
        }",
        warn = state.blockTally?.let {
            it.isRoomWide && kotlin.math.abs(it.count - state.blockLevel) >=
                sk.martinvanco.monad.lab.domain.BlockGuards.LEVEL_TALLY_TOLERANCE
        } ?: false,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { model.onEvent(LabConsoleEvent.StartBlock) },
            enabled = state.isRunning,
        ) { Text(if (active == null) "Start block" else "Start next") }
        OutlinedButton(
            onClick = { model.onEvent(LabConsoleEvent.StopBlock) },
            enabled = active != null,
        ) { Text("Stop block") }
    }
    Text(
        "Starting while one runs auto-stops it and records why. A block still open when the " +
            "session ends is closed automatically and marked as such.",
        fontSize = 10.sp,
    )
}

/**
 * Pre-flight — everything that can be known before ten people are in the room.
 *
 * Kept at the very top, above residency, because it is the panel that subsumes all the ones below
 * it: an operator with two minutes reads this and nothing else.
 */
@Composable
private fun PreflightPanel(state: LabConsoleState, model: LabConsoleScreenModel) =
    Panel("pre-flight") {
        val report = state.preflight
        if (report == null) {
            Text(
                "Not run. It takes the datagram socket for a few seconds and answers the one " +
                    "question worth answering early: would this phone fail the clock gate?",
                fontSize = 10.sp,
            )
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
        OutlinedButton(
            onClick = { model.onEvent(LabConsoleEvent.RunPreflight) },
            enabled = !state.preflightRunning,
        ) { Text(if (state.preflightRunning) "Checking…" else "Run pre-flight") }
    }

@Composable
private fun WarningLine(message: String) {
    Text(
        "! $message",
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun formatElapsed(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    return "${minutes}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun TrafficPanel(state: LabConsoleState) = Panel("illuminator") {
    val traffic = state.traffic
    KeyValue("commanded", "${traffic.commandedRateHz} Hz")
    KeyValue("achieved", "${(traffic.achievedRateHz * 100).toInt() / 100.0} Hz")
    KeyValue("delivered", "${(traffic.deliveredFraction * 100).toInt()} %")
    KeyValue("sent / failed", "${traffic.sent} / ${traffic.failed}", warn = traffic.failed > 0)
    KeyValue(
        "interval CV",
        "${(traffic.intervalCv * 1000).toInt() / 1000.0}",
        warn = traffic.sent > 100 && !traffic.meetsUniformityGate,
    )
    KeyValue("max gap", "${(traffic.maxGapMillis * 10).toInt() / 10.0} ms")
    KeyValue("mean lateness", "${(traffic.meanLatenessMillis * 100).toInt() / 100.0} ms")
    Text("gate: CV ≤ 0.2 on-device (EXP-P2 broadcast baseline was 1.6–2.5)", fontSize = 10.sp)
}

/**
 * The clock, and — the part that matters for block boundaries — **how precise it currently is**.
 *
 * A pass/fail verdict is not enough here. Cycling ramps are 30 s and are T3's primary event set, so
 * block boundaries are held to G4b's 250 ms rather than G4a's 6 s, and a phone drifting toward that
 * budget has to be visible while it can still be fixed — not after the session, when the fold is
 * simply dropped.
 */
@Composable
private fun ClockPanel(state: LabConsoleState) = Panel("clock") {
    val clock = state.clock
    val gate = state.health.clockGate
    KeyValue("samples", clock.samples.toString(), warn = clock.samples == 0)
    KeyValue("offset", "${(clock.offsetMillis * 1000).toInt() / 1000.0} ms")
    KeyValue("rtt", "${(clock.delayMillis * 1000).toInt() / 1000.0} ms")
    KeyValue("skew", "${(clock.skewPpm * 100).toInt() / 100.0} ppm")

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    KeyValue("gate samples", "${gate.sampleCount}", warn = !gate.meetsMinimumSamples)
    KeyValue("fit residual", gate.precisionLine, warn = !gate.meetsAllTestsBudget)
    KeyValue(
        "G4a (6 s, all tests)",
        if (gate.meetsAllTestsBudget) "ok" else "WOULD FAIL",
        warn = !gate.meetsAllTestsBudget,
    )
    KeyValue(
        "G4b (250 ms, T3)",
        if (gate.meetsT3Budget) "ok" else "WOULD FAIL",
        warn = !gate.meetsT3Budget,
    )
    Text(
        "G4b is the budget block boundaries are held to: a cycling ramp is 30 s, so a 6 s error " +
            "mislabels a fifth of it. Rejected bursts still count toward the residual — the filter " +
            "cleans the fit, never the evidence.",
        fontSize = 10.sp,
    )
}

@Composable
private fun WitnessPanel(state: LabConsoleState) = Panel("witness") {
    KeyValue("observations", state.instrument.beaconCount.toString())
    KeyValue("last rssi", state.instrument.lastRssi?.let { "$it dBm" } ?: "—")
    KeyValue("in zones", state.instrument.currentZones.joinToString(", ").ifBlank { "—" })
    state.witnessDiagnostics.forEach { Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
}

/**
 * Ground truth — the people channel.
 *
 * Sits after the witness panel on purpose. Everything above it counts *phones*; this panel is the
 * only place in the console where a number means people, and the operator reads the two next to
 * each other precisely so the gap between them stays visible. That gap is the point: phone-vs-person
 * bias is what a later experiment sets out to measure, so the truth channel must never be derived
 * from the panels above it.
 */
@Composable
private fun GroundTruthPanel(
    state: LabConsoleState,
    model: LabConsoleScreenModel,
    onOpenScanner: () -> Unit,
) = Panel("ground truth") {
    RoomTallyBlock(state, model)

    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

    KeyValue("checked in (this device)", state.groundTruthCount.toString())
    KeyValue("unsent scans", state.groundTruthPending.toString(), warn = state.groundTruthPending > 0)

    FlushConflictBlock(state)

    if (state.config.beacons.zones.isNotEmpty()) {
        Text("zone", fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.config.beacons.zones.forEach { zone ->
                FilterChip(
                    selected = state.groundTruthZoneId == zone.cellId,
                    onClick = { model.onEvent(LabConsoleEvent.SelectGroundTruthZone(zone.cellId)) },
                    label = { Text(zone.label.ifBlank { zone.cellId }, fontSize = 11.sp) },
                )
            }
        }
    }

    val payload = state.groundTruthQrPayload
    if (payload == null) {
        Text(
            "Start a session to generate the check-in code. A code that names no session " +
                "produces scans nothing can be joined to.",
            fontSize = 10.sp,
        )
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = rememberQrKitPainter(data = payload),
                contentDescription = "check-in code",
                modifier = Modifier.size(220.dp),
            )
        }
        // The payload in plain text as well as in the code: a lab tapes a printed copy to the
        // doorframe, and printing happens off this device.
        Text(payload, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(
            "One code per doorway. It toggles: a participant's first scan is an entry, their next " +
                "is an exit, resolved against their own history.",
            fontSize = 10.sp,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpenScanner) { Text("Scan") }
        OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.RefreshGroundTruth) }) {
            Text("Refresh")
        }
        if (state.groundTruthPending > 0) {
            OutlinedButton(
                onClick = { model.onEvent(LabConsoleEvent.FlushGroundTruth) },
                enabled = !state.isBusy,
            ) { Text("Flush") }
        }
    }
    Text(
        "The count above is what this handset has seen — one participant. The room-wide number " +
            "comes from the backend, which aggregates every participant's uploads.",
        fontSize = 10.sp,
    )
}

/**
 * What the last flush's ingest receipt said, and in particular its **conflict count**.
 *
 * The receipt is the first moment an E3 conflict is knowable: a `scan_nonce` arriving with a
 * different participant, zone or direction than one already stored. The room-tally poll shows
 * conflicts the server has already accumulated; this shows the ones this phone just discovered by
 * pushing. By analysis time the affected interval is excluded and there is nobody left to ask what
 * happened, so it has to appear while somebody is still standing in the room.
 *
 * The flush report already carried this. Until now, nothing on the console read it.
 */
@Composable
private fun FlushConflictBlock(state: LabConsoleState) {
    val flush = state.lastFlush ?: return
    val tally = flush.tally
    if (!tally.wasAttempted) return

    KeyValue(
        "last flush → room",
        tally.line ?: "nothing to send",
        warn = !tally.isClean,
    )
    if (tally.hasConflicts) {
        Text(
            "${tally.conflicts} scan-nonce CONFLICT(S) in the last flush — E3 fires on the " +
                "affected interval. Find out now what happened; after the session nobody can.",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (tally.rejected > 0) {
        Text(
            "${tally.rejected} scan(s) rejected by the server and will not be retried.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (flush.artefactsFailed > 0) {
        Text(
            "${flush.artefactsFailed} artefact(s) failed and are still on this phone; " +
                "${flush.discarded} discarded.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * The room-wide tally, and — always — which number this actually is.
 *
 * The design constraint that drives everything here: the local count and the room count differ by
 * a factor of ten in this session, and an operator who mistakes one for the other will record a
 * staircase level that never happened. So the source is a label, not an inference, and it is the
 * largest text in the panel after the number itself. There is deliberately no layout in which a
 * bare integer appears without its provenance next to it.
 */
@Composable
private fun RoomTallyBlock(state: LabConsoleState, model: LabConsoleScreenModel) {
    val source = state.roomTallySource
    val accent = when (source) {
        TallySource.ROOM_LIVE -> MaterialTheme.colorScheme.onSurface
        TallySource.ROOM_STALE, TallySource.DEVICE_ONLY -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                when (source) {
                    TallySource.ROOM_LIVE -> "IN ROOM (all devices)"
                    TallySource.ROOM_STALE -> "IN ROOM (all devices) — STALE"
                    TallySource.DEVICE_ONLY -> "THIS DEVICE ONLY — no room tally"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                when (source) {
                    // Age is stated even when fresh: "4 s ago" is what makes "0 people" readable
                    // as a measurement rather than as a broken poll.
                    TallySource.ROOM_LIVE ->
                        "last poll ${state.roomTallyAgeSeconds ?: 0} s ago"
                    TallySource.ROOM_STALE ->
                        "last successful poll ${state.roomTallyAgeSeconds ?: 0} s ago — " +
                            (state.roomTallyError ?: "not refreshing")
                    TallySource.DEVICE_ONLY ->
                        state.roomTallyError
                            ?: if (state.isRunning) "waiting for first server response"
                            else "start a session to aggregate the room"
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
        }
        Text(
            state.displayedGroundTruthCount.toString(),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = accent,
        )
    }

    val tally = state.roomTally
    if (tally != null) {
        tally.zones.forEach { zone ->
            KeyValue(
                "  ${zone.zoneId}",
                buildString {
                    append(zone.checkedIn)
                    // Only shown when the two definitions disagree, which means somebody scanned
                    // the same direction twice. Printing it always would be noise.
                    if (zone.isInconsistent) append("  (cumulative sum says ${zone.netSum})")
                },
                warn = zone.isInconsistent || zone.conflictCount > 0,
            )
        }

        // The gap between the room total and the sum of the zones is exactly the set of people who
        // walked into a new zone without scanning out of the old one.
        if (tally.overall.zoneSum != tally.overall.checkedIn) {
            Text(
                "zones sum to ${tally.overall.zoneSum} but ${tally.overall.checkedIn} " +
                    "participant(s) are in the room — someone changed zone without scanning out",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.roomTallyConflictCount > 0) {
            // E3. The affected interval leaves every test, and this is the only moment at which a
            // human can still walk over and find out what happened.
            Text(
                "${state.roomTallyConflictCount} scan-nonce conflict(s) — E3 fires on the " +
                    "affected interval; check the tally against the manual sheet now",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
            tally.conflicts.take(3).forEach {
                Text(
                    "  ${it.zoneId} ${it.accepted} vs ${it.rejected}",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    // The pre-registered redundant check. It stays on screen whatever the backend is doing —
    // it is the thing that catches the failure the electronics cannot see, and it is the reason a
    // stale tally is survivable rather than fatal.
    Text(
        "Cross-check against the manual tally sheet at every checkpoint. A discrepancy of " +
            "≥ 1 person fires E3 on the interval between checkpoints.",
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )

    OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.RefreshRoomTally) }) {
        Text("Poll room now", fontSize = 11.sp)
    }
}

@Composable
private fun SessionsPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("sessions") {
    KeyValue("unsynced", state.unsyncedCount.toString(), warn = state.unsyncedCount > 0)
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
                Text(
                    "${if (row.socketPinned) "pinned" else "UNPINNED"} ${row.boundInterface}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                row.uploadError?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
            }
            OutlinedButton(onClick = { model.onEvent(LabConsoleEvent.DeleteSession(row.sessionId)) }) {
                Text("del", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun LogPanel(state: LabConsoleState, model: LabConsoleScreenModel) = Panel("log") {
    state.log.takeLast(40).reversed().forEach { line ->
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
