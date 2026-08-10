package sk.martinvanco.monad.lab.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
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
import dev.icerock.moko.permissions.PermissionsController
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import kotlinx.coroutines.launch
import sk.martinvanco.monad.core.domain.permissions.LabPermission
import sk.martinvanco.monad.core.domain.permissions.PermissionStatus
import sk.martinvanco.monad.core.presentation.components.ScreenWithBackNavigation
import sk.martinvanco.monad.lab.domain.SessionReport
import sk.martinvanco.monad.lab.domain.health.StreamHealth
import sk.martinvanco.monad.lab.domain.health.StreamState
import sk.martinvanco.monad.lab.domain.roundTo

/**
 * "Am I recording?" — the participant's screen.
 *
 * Ordered by the question it answers, not by the architecture it reflects: state, zone, streams,
 * clock, permissions, backlog, last session. A participant reads the first two and stops; an
 * operator reads down until something is red.
 */
class SessionStatusScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<SessionStatusScreenModel>()
        val state by model.state.collectAsState()

        val factory = rememberPermissionsControllerFactory()
        val controller = remember(factory) { factory.createPermissionsController() }
        BindEffect(controller)
        val scope = rememberCoroutineScope()

        // Re-checked on every resume, so a participant who was sent to Settings comes back to a
        // screen that believes them.
        var resumeCount by remember { mutableStateOf(0) }
        LifecycleResumeEffect(Unit) {
            resumeCount++
            onPauseOrDispose { }
        }
        LaunchedEffect(controller, resumeCount) {
            model.onEvent(SessionStatusEvent.PermissionChanged(controller.snapshot()))
            model.onEvent(SessionStatusEvent.Refresh)
        }

        ScreenWithBackNavigation(title = "Session status", onBackClick = { navigator.pop() }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Headline(state)

                if (state.recovered.isNotEmpty()) {
                    Callout(Color(0xFFFEF3C7)) {
                        Text(
                            "${state.recovered.size} session(s) were interrupted and have been " +
                                "recovered. Nothing was lost; they are queued to upload.",
                            fontSize = 12.sp,
                        )
                        state.recovered.forEach {
                            Text(
                                "${it.shortId} · ${it.rows} rows" +
                                    if (!it.monotonicContinuous) " · clock epoch broken" else "",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                if (state.health.streams.isNotEmpty()) {
                    SectionTitle("Streams")
                    state.health.streams.forEach { StreamRow(it) }
                }

                SectionTitle("Clock")
                val gate = state.health.clockGate
                Callout(if (gate.wouldFailGate) Color(0xFFFEE2E2) else Color(0xFFF1F5F9)) {
                    Text(gate.headline, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (gate.sampleCount >= 2) {
                        Text(
                            "skew ${gate.skewPpm.roundTo(1)} ppm · offset ${gate.offsetMillis.roundTo(1)} ms" +
                                (gate.maxFitResidualMillis?.let { " · fit residual ${it.roundTo(2)} ms" } ?: ""),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (gate.note.isNotBlank()) Text(gate.note, fontSize = 11.sp)
                    if (gate.wouldFailGate) {
                        Text(
                            "Tell the operator: this session would be excluded from the analysis.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                SectionTitle("Permissions")
                state.permissions.forEach { status ->
                    PermissionRow(status) {
                        scope.launch {
                            controller.request(status)
                            model.onEvent(SessionStatusEvent.PermissionChanged(controller.snapshot()))
                        }
                    }
                }

                SectionTitle("Waiting to upload")
                if (state.pending.isEmpty) {
                    Text("Nothing. Everything on this phone has been acknowledged.", fontSize = 12.sp)
                } else {
                    state.pending.byArtefact().forEach {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(it.artefact, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("${it.rows} rows", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(
                        "${state.pending.sessionCount} session(s), ${state.pending.totalRows} rows. " +
                            "Nothing is deleted until the server acknowledges it.",
                        fontSize = 11.sp,
                    )
                    if (state.pending.groundTruthNotInTally > 0) {
                        Text(
                            "${state.pending.groundTruthNotInTally} scan(s) not yet in the room " +
                                "tally. They are safe on this phone and in the dataset copy; only " +
                                "the operator's live count is behind.",
                            fontSize = 11.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = { model.onEvent(SessionStatusEvent.Flush) },
                        enabled = !state.isBusy,
                    ) { Text("Send now") }
                }
                state.flushMessage?.let {
                    Callout(Color(0xFFF1F5F9)) { Text(it, fontSize = 12.sp) }
                }

                state.lastSession?.let { report ->
                    SectionTitle("Last session")
                    SessionSummaryCard(report)
                }
            }
        }
    }
}

@Composable
private fun Headline(state: SessionStatusState) {
    val colour = when {
        !state.isRecording -> Color(0xFFF1F5F9)
        state.isNominal -> Color(0xFFDCFCE7)
        state.health.overall == StreamState.DEAD -> Color(0xFFFEE2E2)
        else -> Color(0xFFFEF3C7)
    }
    Surface(modifier = Modifier.fillMaxWidth(), color = colour, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.headline, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(
                state.zone.current?.let { "You are in ${it.zoneId}" } ?: "You are not checked in",
                fontSize = 16.sp,
            )
            val freshest = state.health.streams
                .filter { it.everProduced }
                .minByOrNull { it.silenceMillis }
            Text(
                freshest?.let {
                    "last event ${SessionReport.formatDuration(it.silenceMillis)} ago " +
                        "(${it.stream.label.lowercase()})"
                } ?: "no events recorded yet",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (state.isRecording) {
                Text(
                    "running for ${SessionReport.formatDuration(state.health.sessionElapsedMillis)}",
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun StreamRow(health: StreamHealth) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(health.state.colour()),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(health.stream.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(health.state.wire.uppercase(), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                buildString {
                    append("${health.eventsPerSecond.roundTo(1)}/s")
                    health.expectedRateHz?.let { append(" of ${it.roundTo(1)}/s commanded") }
                    append(" · ${health.totalEvents} total")
                    if (health.everProduced) {
                        append(" · last ${SessionReport.formatDuration(health.silenceMillis)} ago")
                    } else {
                        append(" · nothing yet")
                    }
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (health.hadTrouble) {
                Text(
                    "worst: ${health.worstState.wire} for ${SessionReport.formatDuration(health.troubleMillis)}",
                    fontSize = 11.sp,
                    color = Color(0xFF92400E),
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(status: PermissionStatus, onFix: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !status.granted, onClick = onFix),
        color = if (status.granted) Color(0xFFF1F5F9) else Color(0xFFFEF3C7),
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(status.permission.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(status.action, fontSize = 11.sp)
            }
            if (!status.granted) {
                Text(status.permission.why, fontSize = 11.sp)
                Text("Without it: ${status.permission.ifMissing}", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(report: SessionReport) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = when (report.worstLevel) {
            SessionReport.Level.BAD -> Color(0xFFFEE2E2)
            SessionReport.Level.WARN -> Color(0xFFFEF3C7)
            SessionReport.Level.GOOD -> Color(0xFFF1F5F9)
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(report.headline, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "${report.shortId} · ${SessionReport.formatDuration(report.durationMillis)}" +
                    (report.site.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            report.interruptedReason?.let {
                Text(it, fontSize = 11.sp, color = Color(0xFF92400E))
            }
            report.verdicts.forEach { verdict ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        when (verdict.level) {
                            SessionReport.Level.GOOD -> "ok  "
                            SessionReport.Level.WARN -> "warn"
                            SessionReport.Level.BAD -> "FAIL"
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${verdict.label}: ${verdict.detail}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(
                report.counts.joinToString("  ") { "${it.first} ${it.second}" },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
}

@Composable
private fun Callout(colour: Color, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = colour, shape = RoundedCornerShape(6.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
        }
    }
}

private fun StreamState.colour(): Color = when (this) {
    StreamState.ALIVE -> Color(0xFF22C55E)
    StreamState.IDLE -> Color(0xFF94A3B8)
    StreamState.DEGRADED -> Color(0xFFF59E0B)
    StreamState.STALE -> Color(0xFFF97316)
    StreamState.DEAD -> Color(0xFFDC2626)
    StreamState.NOT_APPLICABLE -> Color(0xFFCBD5E1)
}

/** Current state of every lab permission, in one call. */
private suspend fun PermissionsController.snapshot(): List<PermissionStatus> =
    LabPermission.entries.map { lab ->
        PermissionStatus(
            permission = lab,
            granted = runCatching { isPermissionGranted(lab.permission) }.getOrDefault(false),
            // Only a refusal we have actually seen can be called permanent; the controller cannot
            // be asked, and guessing "permanent" would send a participant to Settings for nothing.
            deniedPermanently = false,
        )
    }

private suspend fun PermissionsController.request(status: PermissionStatus) {
    if (status.deniedPermanently) {
        openAppSettings()
        return
    }
    try {
        providePermission(status.permission.permission)
    } catch (e: DeniedAlwaysException) {
        openAppSettings()
    } catch (e: DeniedException) {
        // Declined once; the row stays and can be tapped again.
    } catch (e: Exception) {
        // Nothing here may take down the status screen — it is the surface a participant uses to
        // find out whether anything is wrong.
    }
}
