package sk.martinvanco.monad.lab.presentation

import androidx.compose.foundation.background
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import sk.martinvanco.monad.lab.domain.CheckInState

/**
 * Check in by scanning whatever card is nearest.
 *
 * The screen it replaces asked for a specific printed payload —
 * `monad://ground-truth/v1?session=…&zone=…` — that exists on no card in the building. The 35 cards
 * in the field carry `https://monad.dubec.dev/m/MONAD-FP-07` and friends, so a participant holding
 * a phone next to a real card was told "that is not a MonadCount check-in code". It also put three
 * blocks of explanation, a zone banner, a receipt, a notice, an error and an unsent-scan counter on
 * one screen, which is six things to read before pressing one button.
 *
 * What is left is the state and the action. Checked in: where, how long, and Check out. Not checked
 * in: one button that opens the camera. Everything else is one line of feedback after a scan.
 */
class CheckInScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<CheckInScreenModel>()
        val state by model.state.collectAsState()
        val checkIn by model.checkInState.collectAsState()
        val now by model.now.collectAsState()

        ScreenWithBackNavigation(title = "Check in", onBackClick = { navigator.pop() }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (val active = checkIn) {
                    is CheckInState.Active -> ActivePanel(
                        place = active.place.display,
                        elapsed = formatElapsed(active.elapsedMillis(now)),
                        isBusy = state.isBusy,
                        onCheckOut = { model.onEvent(CheckInScreenEvent.CheckOut) },
                    )

                    CheckInState.Idle -> IdlePanel()
                }

                if (state.isScanning) {
                    Scanner(
                        onScanned = { model.onEvent(CheckInScreenEvent.Scanned(it)) },
                        onCancel = { model.onEvent(CheckInScreenEvent.StopScan) },
                    )
                } else {
                    CameraGate {
                        Button(
                            onClick = { model.onEvent(CheckInScreenEvent.StartScan) },
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = if (checkIn is CheckInState.Active) {
                                    "Scan another card to move"
                                } else {
                                    "Scan a card"
                                },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                state.message?.let { message ->
                    Feedback(message = message, isError = state.isError) {
                        model.onEvent(CheckInScreenEvent.Dismiss)
                    }
                }

                // Only when there is something to say. A permanent "unsent scans: 0" is a line
                // every participant reads once, learns to ignore, and then misses when it matters.
                if (state.pendingCount > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${state.pendingCount} scan(s) are still on this phone. Nothing is lost " +
                                "by being offline — they go up on their own.",
                            fontSize = 12.sp,
                            color = Muted,
                        )
                        OutlinedButton(
                            onClick = { model.onEvent(CheckInScreenEvent.Flush) },
                            enabled = !state.isBusy,
                        ) { Text("Send now") }
                    }
                }
            }
        }
    }

    /**
     * Checked in: where and for how long, then the way out.
     *
     * The timer is the whole point of the panel — it is the one number that tells a participant the
     * app is still counting them, and it is what the lock-screen indicator shows too, so the two
     * agree when they look at either.
     */
    @Composable
    private fun ActivePanel(
        place: String,
        elapsed: String,
        isBusy: Boolean,
        onCheckOut: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFDCFCE7))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Checked in at", fontSize = 13.sp, color = Color(0xFF166534))
            Text(place, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(elapsed, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color(0xFF166534))
            Text(
                "Your phone is counting this visit. It closes on its own after three hours.",
                fontSize = 12.sp,
                color = Color(0xFF334155),
            )
            Button(
                onClick = onCheckOut,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF166534)),
            ) { Text("Check out", fontWeight = FontWeight.SemiBold) }
        }
    }

    /**
     * Not checked in.
     *
     * Two sentences, and the second is the one that matters: any card counts. That is the whole
     * change from the screen this replaces, and a participant who does not know it will walk to a
     * doorway looking for a code that is not printed anywhere.
     */
    @Composable
    private fun IdlePanel() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF1F5F9))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Not checked in", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(
                "Scan any MonadCount card where you are sitting and the phone counts how long you " +
                    "stay. This is the only thing in the app that counts PEOPLE rather than phones.",
                fontSize = 13.sp,
                color = Color(0xFF334155),
            )
        }
    }

    @Composable
    private fun Scanner(onScanned: (String) -> Unit, onCancel: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            QrScanner(
                modifier = Modifier.fillMaxSize(),
                flashlightOn = false,
                cameraLens = CameraLens.Back,
                openImagePicker = false,
                onCompletion = onScanned,
                imagePickerHandler = { },
                // Transient decode misses fire constantly while a code is being lined up. Showing
                // them would bury the one message that matters.
                onFailure = { Napier.d("[check-in] qr decode miss: $it") },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Text("Point at any card near you.", fontSize = 12.sp, color = Muted)
        }
    }

    @Composable
    private fun Feedback(message: String, isError: Boolean, onDismiss: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isError) Color(0xFFFEF3C7) else Color(0xFFEFF6FF))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(message, fontSize = 13.sp, color = Ink)
            TextButton(onClick = onDismiss) { Text("OK", fontSize = 13.sp) }
        }
    }

    /**
     * Camera permission pre-flight, mirroring the quest QR step exactly.
     *
     * The re-check on resume matters: a participant sent to Settings comes back to a composable
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

        androidx.compose.runtime.LaunchedEffect(controller, resumeCount) {
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
}

private val Ink = Color(0xFF0F142F)
private val Muted = Color(0xFF64748B)
