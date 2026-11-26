package dev.dubec.monad.quests.presentation.components.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.icerock.moko.permissions.DeniedAlwaysException
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.camera.CAMERA
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import kotlinx.coroutines.launch
import qrscanner.CameraLens
import qrscanner.QrScanner
import dev.dubec.monad.quests.data.dto.ActiveTaskDto
import dev.dubec.monad.quests.data.dto.QrCodeConfig
import dev.dubec.monad.quests.data.dto.TaskConfigParser
import dev.dubec.monad.core.config.isDebug
import dev.dubec.monad.core.presentation.components.PermissionRequiredCard
import dev.dubec.monad.quests.presentation.components.QuestStepCard
import dev.dubec.monad.quests.presentation.components.ScannerDialog

/**
 * QR Code scanning step component
 * Displays camera view for QR code scanning and validates scanned codes
 */
@Composable
fun QrCodeStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Permission pre-flight check
    val permFactory = rememberPermissionsControllerFactory()
    val permController = remember(permFactory) { permFactory.createPermissionsController() }
    BindEffect(permController)

    var permissionGranted by remember { mutableStateOf(false) }
    var permissionDeniedPermanently by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Track resume count to re-trigger permission checks when returning from Settings
    var resumeCount by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCount++
        onPauseOrDispose { }
    }

    // Recheck permission on every resume
    LaunchedEffect(permController, resumeCount) {
        val granted = permController.isPermissionGranted(Permission.CAMERA)
        permissionGranted = granted
        if (granted) permissionDeniedPermanently = false
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
                        // User denied once, stay on screen
                    } catch (e: Exception) {
                        // Platform error fallback
                        permissionDeniedPermanently = false
                    }
                }
            },
            onOpenSettings = { permController.openAppSettings() }
        )
        return
    }

    val config = remember(task) {
        TaskConfigParser.getQrCodeConfig(task)
    }

    var isScanning by remember { mutableStateOf(false) }

    // Latches for the lifetime of this step: a completed scan must not be
    // re-delivered by a callback that was already in flight.
    var hasCompleted by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        content = {
            QrCodeContent(
                title = task.name,
                config = config,
                isScanning = isScanning,
                errorMessage = errorMessage,
                onClose = { isScanning = false },
                onScan = { scannedValue ->
                    // IP-128 — re-entrancy guard. The scanner keeps firing its
                    // callback for as long as the code stays in frame, so without
                    // this `onComplete()` runs repeatedly and the step advances
                    // several times from one physical scan. Setting `isScanning`
                    // inside onSuccess is not enough: the callbacks already queued
                    // arrive afterwards. The ground-truth scanner drops repeats the
                    // same way (GroundTruthScanScreenModel).
                    if (!isScanning || hasCompleted) return@QrCodeContent
                    handleQrCodeScanned(
                        scannedValue = scannedValue,
                        expectedValue = config?.expectedValue,
                        onSuccess = {
                            hasCompleted = true
                            isScanning = false
                            errorMessage = null
                            onComplete()
                        },
                        onError = { error ->
                            errorMessage = error
                        }
                    )
                }
            )
        },
        actions = {
            QrCodeActions(
                isScanning = isScanning,
                onStartScan = {
                    isScanning = true
                    errorMessage = null
                }
            )
        }
    )
}

/**
 * Content section: Camera view and error messages
 */
@Composable
private fun QrCodeContent(
    title: String,
    config: QrCodeConfig?,
    isScanning: Boolean,
    errorMessage: String?,
    onClose: () -> Unit,
    onScan: (String) -> Unit
) {
    // The step scrolls, so the camera opens over the whole screen, not inline below the text.
    if (isScanning) {
        ScannerDialog(
            title = title,
            hint = config?.location?.let { "Location: $it" },
            error = errorMessage,
            onClose = onClose,
        ) { cameraModifier ->
            Box(modifier = cameraModifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
                QrScanner(
                    modifier = Modifier.fillMaxSize(),
                    flashlightOn = false,
                    cameraLens = CameraLens.Back,
                    openImagePicker = false,
                    onCompletion = { scannedValue -> onScan(scannedValue) },
                    imagePickerHandler = { /* Not used */ },
                    onFailure = { errorMsg -> if (isDebug()) println("QR Scan failed: $errorMsg") }
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Location hint
        config?.location?.let { location ->
            Text(
                text = "Location: $location",
                fontSize = 13.sp,
                color = Color(0xFF64748B),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        if (!isScanning) {
            // Placeholder when not scanning
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Tap 'Scan QR Code' to start",
                    fontSize = 14.sp,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Error message
        errorMessage?.let { error ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFEE2E2), // red-100
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = error,
                    fontSize = 13.sp,
                    color = Color(0xFFDC2626), // red-600
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * Actions section: Scan button
 */
@Composable
private fun QrCodeActions(
    isScanning: Boolean,
    onStartScan: () -> Unit
) {
    Button(
        onClick = onStartScan,
        enabled = !isScanning,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF5B6ECC)
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Scan QR Code",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

/**
 * Handle QR code validation logic
 */
private fun handleQrCodeScanned(
    scannedValue: String,
    expectedValue: String?,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    if (expectedValue == null) {
        onError("Configuration error: No expected QR code value")
        return
    }

    if (scannedValue.equals(expectedValue, ignoreCase = true)) {
        onSuccess()
    } else {
        onError("This is not the expected QR code. Please try again")
    }
}
