package sk.martinvanco.monad.lab.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import sk.martinvanco.monad.core.presentation.components.ScreenWithBackNavigation

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
                ResidencyPanel(state, model)
                CollectorPanel(state, model)
                ProfilePanel(state, model)
                ControlPanel(state, model)
                TrafficPanel(state)
                ClockPanel(state)
                WitnessPanel(state)
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

@Composable
private fun ClockPanel(state: LabConsoleState) = Panel("clock") {
    val clock = state.clock
    KeyValue("samples", clock.samples.toString(), warn = clock.samples == 0)
    KeyValue("offset", "${(clock.offsetMillis * 1000).toInt() / 1000.0} ms")
    KeyValue("rtt", "${(clock.delayMillis * 1000).toInt() / 1000.0} ms")
    KeyValue("skew", "${(clock.skewPpm * 100).toInt() / 100.0} ppm")
    Text("gate: residual < 5 ms over a 30-minute session", fontSize = 10.sp)
}

@Composable
private fun WitnessPanel(state: LabConsoleState) = Panel("witness") {
    KeyValue("observations", state.instrument.beaconCount.toString())
    KeyValue("last rssi", state.instrument.lastRssi?.let { "$it dBm" } ?: "—")
    KeyValue("in zones", state.instrument.currentZones.joinToString(", ").ifBlank { "—" })
    state.witnessDiagnostics.forEach { Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
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
