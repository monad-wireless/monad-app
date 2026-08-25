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
import org.koin.compose.koinInject
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.data.dto.TaskConfigParser
import sk.martinvanco.monad.quests.domain.verifyAssociation
import sk.martinvanco.monad.quests.presentation.components.QuestStepCard

/**
 * IP-140 — the illuminator arm's gate: are we associated, is the socket pinned, and can we actually
 * reach the collector?
 *
 * **This step used to lie.** Until now `connect_to_ap` routed to `TextBoxStep`, which renders a
 * title and a Continue button, so it reported success whether or not the handset had joined
 * anything at all. `BleAdvertiseStep`'s own documentation names it as the failure mode not to
 * repeat, and this is that repair.
 *
 * It does not associate. The instrument does that, once, in [LabInstrument.start] — one place joins
 * a network, and two would eventually disagree about which one the socket is bound to. What this
 * step does is *verify*, and refuse when the verification fails:
 *
 * 1. a session is running,
 * 2. the session was asked to play the illuminator role at all,
 * 3. the socket is pinned to a real interface,
 * 4. **a clock exchange has completed over that socket** — the only one of the four that proves a
 *    round trip to the collector rather than an intention to make one. A pinned UDP socket is not
 *    evidence of reachability; UDP is connectionless and `open()` succeeds against a host that is
 *    not there. A returned four-timestamp burst is evidence.
 *
 * Today it will refuse on this deployment, and that is correct rather than a regression. Monitor-
 * mode injection replaced the 2.4 GHz soft AP on 2026-08-11 (0.62 Hz delivered against 24.79 Hz)
 * and on 5 GHz an access point is impossible outright — Intel LAR blocks beaconing on every iwlmvm
 * client card. So the lab bundle carries no access point and no collector. A quest that declares an
 * illuminator arm and cannot get one has produced nothing, and it should say so at step one instead
 * of at analysis time.
 *
 * The credential is never here. Step config is served to every authenticated caller, so it carries
 * only `ap_id` and the handset reads the SSID and the key from the lab bundle — the same rule
 * `ble_advertise` follows for the advertise namespace.
 */
@Composable
fun ConnectToApStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    instrument: LabInstrument = koinInject(),
) {
    val config = remember(task) { TaskConfigParser.getConnectToApConfig(task) }
    val state by instrument.state.collectAsState()
    val clock by instrument.clockEstimate.collectAsState()

    val verdict = remember(state, clock, config) {
        verifyAssociation(
            sessionRunning = state.isRunning,
            illuminatorRequested = state.request?.emit == true,
            commandedApId = config?.apId.orEmpty(),
            joinedApId = state.request?.accessPoint?.id.orEmpty(),
            joinedSsid = state.request?.accessPoint?.ssid.orEmpty(),
            socketPinned = state.socketPinned,
            boundInterface = state.boundInterface,
            clockSamples = clock.samples,
        )
    }

    var hasCompleted by remember { mutableStateOf(false) }

    // Complete on its own the moment the route is verified. There is nothing for a participant to
    // decide here — either the phone can reach the collector or it cannot.
    LaunchedEffect(verdict.verified) {
        if (verdict.verified && !hasCompleted) {
            hasCompleted = true
            onComplete()
        }
    }

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (verdict.verified) Color(0xFF22C55E) else Color(0xFFEF4444)),
                    )
                    Text(
                        text = if (verdict.verified) "Connected and reachable" else "Not connected",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (verdict.verified) Color(0xFF166534) else Color(0xFF991B1B),
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (verdict.verified) Color(0xFFDCFCE7) else Color(0xFFFEE2E2),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = verdict.reason,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = if (verdict.verified) Color(0xFF166534) else Color(0xFF991B1B),
                        modifier = Modifier.padding(12.dp),
                    )
                }

                if (state.boundInterface.isNotBlank()) {
                    Text(
                        text = "interface ${state.boundInterface} · clock samples ${clock.samples}",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                    )
                }
            }
        },
    )
}
