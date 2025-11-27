package sk.martinvanco.monad.quests.presentation.components.steps

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sk.martinvanco.monad.ble.domain.BleScanner
import sk.martinvanco.monad.quests.domain.ActiveTaskDto
import sk.martinvanco.monad.quests.domain.BleDeviceConfig
import sk.martinvanco.monad.quests.domain.TaskConfigParser
import sk.martinvanco.monad.quests.presentation.components.QuestStepCard
import kotlin.math.pow

/**
 * BLE Device finding step component
 * Displays signal strength indicator and requires manual confirmation when device is found
 */
@Composable
fun BleDeviceStep(
    stepNumber: Int,
    task: ActiveTaskDto,
    onComplete: () -> Unit,
    onReportIssue: () -> Unit,
    modifier: Modifier = Modifier,
    bleScanner: BleScanner = koinInject()
) {
    val config = remember(task) {
        TaskConfigParser.getBleDeviceConfig(task)
    }

    var isScanning by remember { mutableStateOf(false) }
    var deviceFound by remember { mutableStateOf(false) }
    var signalStrength by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showMoveHint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Handle BLE scanning lifecycle
    LaunchedEffect(task.status) {
        // Stop scanning when step is no longer active
        if (task.status != sk.martinvanco.monad.quests.domain.TaskStatus.ACTIVE) {
            bleScanner.stopScanning()
        }
    }

    // Timer to show "try moving around" hint after 10 seconds of no detection
    LaunchedEffect(isScanning, signalStrength) {
        if (isScanning && signalStrength == null) {
            showMoveHint = false
            delay(10000) // Wait 10 seconds
            if (signalStrength == null) {
                showMoveHint = true
            }
        } else {
            showMoveHint = false
        }
    }

    // Collect BLE advertisements
    LaunchedEffect(isScanning) {
        if (isScanning && config != null) {
            bleScanner.advertisements
                .onEach { advertisement ->
                    // Log all BLE advertisements
                    println("BLE Advertisement received: ${advertisement.name} (${advertisement.address}) - RSSI: ${advertisement.rssi} dBm")

                    // Filter for the target device by MAC address or name
                    val isTargetDevice = if (config.deviceId.isNotBlank()) {
                        // Filter by MAC address (device ID)
                        advertisement.address.equals(config.deviceId, ignoreCase = true)
                    } else {
                        // Filter by device name
                        advertisement.name?.equals(config.deviceName, ignoreCase = true) == true
                    }

                    if (isTargetDevice) {
                        println("BLE Target device found! RSSI: ${advertisement.rssi} dBm")
                        signalStrength = advertisement.rssi

                        // Mark as found when signal is strong enough
                        if (advertisement.rssi > -60 && !deviceFound) {
                            deviceFound = true
                            println("BLE Device marked as found (RSSI > -60)")
                        }
                    }
                }
                .catch { error ->
                    // Handle any Flow collection errors (e.g., Bluetooth disabled)
                    isScanning = false
                    errorMessage = "Scanning error: ${error.message}"
                }
                .launchIn(this)
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            bleScanner.stopScanning()
        }
    }

    QuestStepCard(
        stepNumber = stepNumber,
        title = task.name,
        description = task.description,
        status = task.status,
        modifier = modifier,
        content = {
            BleDeviceContent(
                config = config,
                isScanning = isScanning,
                signalStrength = signalStrength,
                errorMessage = errorMessage,
                showMoveHint = showMoveHint
            )
        },
        actions = {
            BleDeviceActions(
                isScanning = isScanning,
                deviceFound = deviceFound,
                onStartScan = {
                    scope.launch {
                        errorMessage = null

                        val result = bleScanner.startScanning()
                        result.onSuccess {
                            isScanning = true
                        }.onFailure { error ->
                            isScanning = false
                            errorMessage = "Failed to start scanning: ${error.message}"
                        }
                    }
                },
                onConfirmFound = {
                    bleScanner.stopScanning()
                    onComplete()
                }
            )
        },
        onReportIssue = onReportIssue
    )
}

/**
 * Content section: Signal strength visualization
 */
@Composable
private fun BleDeviceContent(
    config: BleDeviceConfig?,
    isScanning: Boolean,
    signalStrength: Int?,
    errorMessage: String?,
    showMoveHint: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Device info
        config?.let { cfg ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF8FAFC))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Looking for: ${cfg.deviceName}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF0F172A)
                )
                if (cfg.deviceId.isNotBlank()) {
                    Text(
                        text = "Device ID: ${cfg.deviceId}",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                } else {
                    Text(
                        text = "Filtering by device name",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        }

        // Signal strength visualization
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isScanning && signalStrength != null) {
                // Device detected - show colored signal indicator
                SignalStrengthIndicator(rssi = signalStrength)
            } else if (isScanning) {
                // Scanning but no device detected yet - show gray circle
                FindingDeviceIndicator()
            } else {
                Text(
                    text = "Tap 'Start Scanning' to begin",
                    fontSize = 14.sp,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center
                )
            }
        }

        // "Try moving around" hint
        if (showMoveHint && signalStrength == null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFEF3C7), // amber-100
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "💡",
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Device not found yet. Try moving around slowly.",
                        fontSize = 13.sp,
                        color = Color(0xFF92400E), // amber-800
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Error message
        errorMessage?.let { error ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFEE2E2),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = error,
                    fontSize = 13.sp,
                    color = Color(0xFFDC2626),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * Gray circle shown while searching for device
 */
@Composable
private fun FindingDeviceIndicator() {
    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(Color(0xFF94A3B8)), // gray-400
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Finding",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = "device...",
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

/**
 * Visual indicator for BLE signal strength - simplified to show only circle
 */
@Composable
private fun SignalStrengthIndicator(rssi: Int) {
    val distance = calculateDistance(rssi)
    val (color, label) = getSignalInfo(rssi)

    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(300)
    )

    val animatedScale by animateFloatAsState(
        targetValue = when {
            rssi > -60 -> 1.2f
            rssi > -70 -> 1.0f
            else -> 0.8f
        },
        animationSpec = tween(300)
    )

    // Only show the circle with signal info
    Box(
        modifier = Modifier
            .size(140.dp)
            .scale(animatedScale)
            .clip(CircleShape)
            .background(animatedColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "$rssi dBm",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "~${formatDistance(distance)}m",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.95f)
            )
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

/**
 * Actions section: Start scanning / Found It button
 */
@Composable
private fun BleDeviceActions(
    isScanning: Boolean,
    deviceFound: Boolean,
    onStartScan: () -> Unit,
    onConfirmFound: () -> Unit
) {
    Button(
        onClick = {
            if (deviceFound) {
                onConfirmFound()
            } else {
                onStartScan()
            }
        },
        enabled = if (deviceFound) true else !isScanning,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (deviceFound) Color(0xFF22C55E) else Color(0xFF5B6ECC) // green if found, blue otherwise
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            text = when {
                deviceFound -> "Found It"
                isScanning -> "Scanning..."
                else -> "Start Scanning"
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

// ============================================================================
// Helper Functions
// ============================================================================

/**
 * Calculate approximate distance from RSSI value
 * Formula: distance = 10 ^ ((Measured Power - RSSI) / (10 * N))
 * N = Path loss exponent (typically 2-4, we use 2.5)
 * Measured Power = RSSI at 1 meter (typically -59 dBm)
 */
private fun calculateDistance(rssi: Int): Double {
    val measuredPower = -59
    val pathLossExponent = 2.5

    if (rssi == 0) return -1.0

    val ratio = (measuredPower - rssi) / (10.0 * pathLossExponent)
    return 10.0.pow(ratio)
}

/**
 * Format distance to 1 decimal place
 */
private fun formatDistance(distance: Double): String {
    val rounded = (distance * 10).toInt() / 10.0
    val intPart = rounded.toInt()
    val decimalPart = ((rounded - intPart) * 10).toInt()
    return "$intPart.$decimalPart"
}

/**
 * Get color and label based on signal strength
 */
private fun getSignalInfo(rssi: Int): Pair<Color, String> {
    return when {
        rssi > -60 -> Color(0xFF22C55E) to "Very Close" // green-500
        rssi > -70 -> Color(0xFF3B82F6) to "Close" // blue-500
        rssi > -80 -> Color(0xFFF59E0B) to "Medium" // amber-500
        else -> Color(0xFFEF4444) to "Far" // red-500
    }
}

