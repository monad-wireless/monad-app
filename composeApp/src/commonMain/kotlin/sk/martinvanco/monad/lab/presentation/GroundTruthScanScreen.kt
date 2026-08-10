package sk.martinvanco.monad.lab.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import sk.martinvanco.monad.lab.domain.GroundTruthDirection

/**
 * Participant check-in / check-out.
 *
 * The camera is reached through the same boundary the quest QR step already uses — the QRKit
 * multiplatform scanner behind moko's permission controller — rather than through a second,
 * parallel platform abstraction. There is one way this app talks to a camera.
 *
 * Two things are always on screen, above everything else: **which zone you are in**, and **what the
 * last scan actually did**. The session runs three zones inside one hall; a participant who cannot
 * see which one they are checked into will either scan twice or not at all, and both corrupt the
 * only stream in the system that counts people rather than phones.
 */
class GroundTruthScanScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<GroundTruthScanScreenModel>()
        val state by model.state.collectAsState()

        ScreenWithBackNavigation(title = "Check in / out", onBackClick = { navigator.pop() }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZoneBanner(state)

                Text(
                    "Scan the code at the door on your way in, and again on your way out. " +
                        "This is what tells the experiment how many people were in the room — " +
                        "your phone cannot answer that on its own.",
                    fontSize = 12.sp,
                )

                CameraGate {
                    ScannerSection(state = state, onEvent = model::onEvent)
                }

                state.receipt?.takeIf { !it.isDuplicate }?.let { receipt ->
                    val event = receipt.primary
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                if (event.direction == GroundTruthDirection.IN) "CHECKED IN"
                                else "CHECKED OUT",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                            Text(
                                "zone ${event.zoneId}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            receipt.impliedExits.forEach {
                                Text(
                                    "also checked out of ${it.zoneId}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(
                                "session ${event.labSessionId.take(8)} · ${event.wallMillis} ms",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                state.notice?.let { notice ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(notice, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                    }
                }

                state.error?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(error, fontSize = 12.sp)
                            Text(
                                "Nothing was recorded. Try scanning again, or tell the operator.",
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                Text(
                    "unsent scans: ${state.pendingCount}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                state.flushMessage?.let {
                    Text(it, fontSize = 11.sp)
                }
                if (state.pendingCount > 0) {
                    OutlinedButton(
                        onClick = { model.onEvent(GroundTruthScanEvent.Flush) },
                        enabled = !state.isBusy,
                    ) { Text("Send now") }
                    Text(
                        "Scans are kept on the phone until the server acknowledges them. " +
                            "Nothing is lost by being offline.",
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

/**
 * The one line a participant should be able to read at arm's length.
 *
 * Deliberately the first thing on the screen, and deliberately phrased as a place rather than a
 * status code: "You are in ZONE-B" is answerable by a student; "membership: resolved" is not.
 */
@Composable
private fun ZoneBanner(state: GroundTruthScanState) {
    val zone = state.zone.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (zone != null) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                if (zone != null) "You are in ${zone.zoneId}" else "You are not checked in",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = if (zone != null) Color(0xFF166534) else Color(0xFF334155),
            )
            Text(
                if (zone != null) {
                    "Scan another zone's code to move there — this app checks you out of " +
                        "${zone.zoneId} automatically."
                } else {
                    "Scan the code on the doorframe of the zone you are entering."
                },
                fontSize = 12.sp,
            )
            if (state.zone.isAmbiguous) {
                Text(
                    "Also still checked into: " +
                        state.zone.ambiguous.joinToString { it.zoneId } +
                        ". Scan that zone's code to check out, or tell the operator.",
                    fontSize = 11.sp,
                    color = Color(0xFF92400E),
                )
            }
        }
    }
}

@Composable
private fun ScannerSection(
    state: GroundTruthScanState,
    onEvent: (GroundTruthScanEvent) -> Unit,
) {
    if (state.isScanning) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            QrScanner(
                modifier = Modifier.fillMaxSize(),
                flashlightOn = false,
                cameraLens = CameraLens.Back,
                openImagePicker = false,
                onCompletion = { onEvent(GroundTruthScanEvent.Scanned(it)) },
                imagePickerHandler = { },
                // Transient decode misses fire here constantly while the code is being lined up.
                // Surfacing them would bury the one message that matters, and clearing state on
                // them would wipe a confirmation the participant is still reading.
                onFailure = { Napier.d("[lab] qr decode miss: $it") },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onEvent(GroundTruthScanEvent.StopScan) }) { Text("Cancel") }
            Text("Point at the code on the doorframe.", fontSize = 11.sp)
        }
    } else {
        Button(
            onClick = { onEvent(GroundTruthScanEvent.StartScan) },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.zone.isCheckedIn) "Scan to move zone or check out" else "Scan check-in code"
            )
        }
    }
}

/**
 * Camera permission pre-flight, mirroring the quest QR step exactly.
 *
 * The re-check on resume matters: a participant who was sent to Settings comes back to a composable
 * that would otherwise still believe it was denied.
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
                        // Denied once; the card stays and the participant can try again.
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
