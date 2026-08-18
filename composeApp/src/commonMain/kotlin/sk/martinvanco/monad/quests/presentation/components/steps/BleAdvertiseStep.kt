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
import dev.icerock.moko.permissions.bluetooth.BLUETOOTH_ADVERTISE
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sk.martinvanco.monad.core.presentation.components.PermissionRequiredCard
import sk.martinvanco.monad.lab.domain.BroadcastReport
import sk.martinvanco.monad.lab.domain.LabInstrument
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.data.dto.TaskConfigParser
import sk.martinvanco.monad.quests.presentation.components.QuestStepCard

/**
 * Identity-broadcast step: put the session's BLE frame on air for the configured duration, so the
 * fleet's passive scan can observe this handset while the participant walks.
 *
 * The broadcast is driven through [LabInstrument], never directly: the instrument owns the session
 * identity the frame derives from, writes the `broadcast_start` / `broadcast_stop` markers the
 * fleet-side join reads, and records the accepted radio settings in the sidecar. Without a running
 * instrument session there is no identity to advertise, and the step says so instead of rendering
 * a card a participant can tap through — a broadcast step that silently completes while nothing
 * went on air is the exact failure mode `connect_to_ap` has today, and it must not be repeated.
 *
 * Cleanup story: the instrument auto-stops the broadcast when the configured duration elapses, and
 * a session close stops it too, so an abandoned step cannot leave the frame on air.
 */
@Composable
fun BleAdvertiseStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    instrument: LabInstrument = koinInject(),
) {
    // Permission pre-flight, mirroring BleDeviceStep. On Android 12+ this is BLUETOOTH_ADVERTISE;
    // below that, and on iOS, the platform grants it with the general Bluetooth permission.
    val permFactory = rememberPermissionsControllerFactory()
    val permController = remember(permFactory) { permFactory.createPermissionsController() }
    BindEffect(permController)

    var permissionGranted by remember { mutableStateOf(false) }
    var permissionDeniedPermanently by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(permController) {
        permissionGranted = permController.isPermissionGranted(Permission.BLUETOOTH_ADVERTISE)
    }

    if (!permissionGranted) {
        PermissionRequiredCard(
            permissionName = "Bluetooth broadcasting",
            deniedPermanently = permissionDeniedPermanently,
            onRequestPermission = {
                scope.launch {
                    try {
                        permController.providePermission(Permission.BLUETOOTH_ADVERTISE)
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

    val config = remember(task) { TaskConfigParser.getBleAdvertiseConfig(task) }
    val totalSeconds = config?.durationSeconds ?: 0

    var remainingSeconds by remember { mutableStateOf(totalSeconds) }
    var isRunning by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<BroadcastReport?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val instrumentState by instrument.state.collectAsState()
    val broadcasting by instrument.isBroadcasting.collectAsState(initial = false)

    // Countdown, and completion when it lands. The instrument's own duration job takes the frame
    // off the air; the belt-and-braces stop here is idempotent.
    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (isActive && remainingSeconds > 0) {
                delay(1000)
                remainingSeconds--
            }
            if (remainingSeconds == 0) {
                instrument.stopBroadcast("step complete")
                delay(500)
                onComplete()
            }
        }
    }

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        content = {
            BleAdvertiseContent(
                configured = config != null,
                sessionRunning = instrumentState.isRunning,
                isRunning = isRunning,
                broadcasting = broadcasting,
                remainingSeconds = remainingSeconds,
                totalSeconds = totalSeconds,
                report = report,
                errorMessage = errorMessage,
            )
        },
        actions = {
            if (!isRunning && config != null && instrumentState.isRunning) {
                Button(
                    onClick = {
                        scope.launch {
                            instrument.startBroadcast(
                                durationSeconds = config.durationSeconds,
                                intervalMs = config.advIntervalMs,
                                txPower = config.txPower,
                            ).onSuccess {
                                report = it
                                errorMessage = null
                                remainingSeconds = totalSeconds
                                isRunning = true
                            }.onFailure {
                                errorMessage = it.message ?: "broadcast refused"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B6ECC)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Start Broadcasting",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        },
    )
}

@Composable
private fun BleAdvertiseContent(
    configured: Boolean,
    sessionRunning: Boolean,
    isRunning: Boolean,
    broadcasting: Boolean,
    remainingSeconds: Int,
    totalSeconds: Int,
    report: BroadcastReport?,
    errorMessage: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            !configured -> Notice(
                text = "This step has no broadcast configuration. It cannot run — tell the operator.",
                background = Color(0xFFFEE2E2), // red-100
                foreground = Color(0xFF991B1B), // red-800
            )

            !sessionRunning -> Notice(
                text = "The measurement session is not running, so there is no identity to " +
                    "broadcast. Retry the instrument from the warning banner, or tell the operator.",
                background = Color(0xFFFEE2E2),
                foreground = Color(0xFF991B1B),
            )

            isRunning -> Notice(
                text = "Keep the app open and the screen on. On iPhone, leaving the app takes " +
                    "the broadcast off the air and the walk stops counting.",
                background = Color(0xFFFEF3C7), // amber-100
                foreground = Color(0xFF92400E), // amber-800
            )
        }

        errorMessage?.let {
            Notice(
                text = "Broadcast refused: $it",
                background = Color(0xFFFEE2E2),
                foreground = Color(0xFF991B1B),
            )
        }

        if (isRunning) {
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
                    text = if (broadcasting) "On air" else "NOT on air",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (broadcasting) Color(0xFF166534) else Color(0xFF991B1B),
                )
                report?.let {
                    Text(
                        text = "· interval ${it.acceptedInterval}",
                        fontSize = 13.sp,
                        color = Color(0xFF64748B),
                    )
                }
            }

            LinearProgressIndicator(
                progress = {
                    if (totalSeconds == 0) 0f
                    else (totalSeconds - remainingSeconds).toFloat() / totalSeconds.toFloat()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF5B6ECC),
                trackColor = Color(0xFFE2E8F0),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formatSeconds(if (isRunning) remainingSeconds else totalSeconds),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRunning) Color(0xFF0F172A) else Color(0xFF64748B),
            )
        }
    }
}

@Composable
private fun Notice(text: String, background: Color, foreground: Color) {
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

private fun formatSeconds(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "${if (minutes < 10) "0$minutes" else "$minutes"}:${if (secs < 10) "0$secs" else "$secs"}"
}
