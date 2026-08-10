package sk.martinvanco.monad.quests.presentation.components.steps

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
import sk.martinvanco.monad.quests.data.dto.ActiveTaskDto
import sk.martinvanco.monad.quests.data.dto.QrCodeConfig
import sk.martinvanco.monad.quests.data.dto.TaskConfigParser
import sk.martinvanco.monad.core.config.isDebug
import sk.martinvanco.monad.core.presentation.components.PermissionRequiredCard
import sk.martinvanco.monad.quests.presentation.components.QuestStepCard

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
    var errorMessage by remember { mutableStateOf<String?>(null) }

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        content = {
            QrCodeContent(
                config = config,
                isScanning = isScanning,
                errorMessage = errorMessage,
                onScan = { scannedValue ->
                    handleQrCodeScanned(
                        scannedValue = scannedValue,
                        expectedValue = config?.expectedValue,
                        onSuccess = {
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
    config: QrCodeConfig?,
    isScanning: Boolean,
    errorMessage: String?,
    onScan: (String) -> Unit
) {
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

        // Camera view / QR Scanner
        if (isScanning) {
            // Actual QR scanner with camera view
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                QrScanner(
                    modifier = Modifier.fillMaxSize(),
                    flashlightOn = false,
                    cameraLens = CameraLens.Back,
                    openImagePicker = false,
                    onCompletion = { scannedValue ->
                        onScan(scannedValue)
                    },
                    imagePickerHandler = { /* Not used */ },
                    onFailure = { errorMsg ->
                        // Handle scan failure - could be logged or shown to user
                        if (isDebug()) println("QR Scan failed: $errorMsg")
                    }
                )
            }
        } else {
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
