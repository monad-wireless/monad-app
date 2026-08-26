package sk.martinvanco.monad.scan.presentation

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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.permissions.DeniedAlwaysException
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.camera.CAMERA
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import kotlinx.coroutines.launch
import qrscanner.CameraLens
import qrscanner.QrScanner
import sk.martinvanco.monad.core.deeplink.DeepLink
import sk.martinvanco.monad.core.deeplink.DeepLinkParser
import sk.martinvanco.monad.core.presentation.components.PermissionRequiredCard
import sk.martinvanco.monad.device.presentation.DeviceScreen
import sk.martinvanco.monad.marker.presentation.MarkerScreen

/**
 * The scan shortcut — one camera, dispatched on what it read.
 *
 * `Marker Placement Record.md` ranked this first of the app changes worth making, and
 * said why: *"Per zone transition it costs six actions across two scanners. It will
 * fail with a cohort."* A participant standing next to a code should not have to know
 * which screen it belongs to before they can point a camera at it.
 *
 * So this screen knows nothing about quests. It reads a code, hands it to
 * [DeepLinkParser] — the same parser the operating system's deep links go through, so
 * there is one grammar and not two — and routes:
 *
 * - a marker card (`/m/<CODE>`) to [MarkerScreen], which resolves it to a quest that
 *   accepts it and starts the run with the scan already counted,
 * - a node sticker (`/d/<slug>`) to [DeviceScreen].
 *
 * **Ground-truth tickets are deliberately not handled here.** `monad://ground-truth/v1?…`
 * is the other grammar in this lab and it writes to the people tally rather than to a
 * quest. Those codes are session-scoped and shown on a screen rather than printed on
 * the wall cards, and the two channels must never be derived from each other — so the
 * check-in screen keeps its own scanner, and a ticket read here says so rather than
 * being quietly filed as a quest scan.
 */
class ScanShortcutScreen : Screen {

    override val key: String get() = "scan-shortcut"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val permFactory = rememberPermissionsControllerFactory()
        val permController = remember(permFactory) { permFactory.createPermissionsController() }
        BindEffect(permController)

        var permissionGranted by remember { mutableStateOf(false) }
        var permissionDeniedPermanently by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        // Latched: the scanner fires its callback for as long as a code stays in frame,
        // and without this one physical scan pushes the destination several times.
        var handled by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(permController) {
            permissionGranted = permController.isPermissionGranted(Permission.CAMERA)
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Point at any code",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A),
                )
                Text(
                    text = "A card on a wall or a sticker on one of the grey boxes. We will work " +
                        "out what it is.",
                    fontSize = 14.sp,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center,
                )

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
                                    // Denied once; stay on the card.
                                } catch (e: Exception) {
                                    permissionDeniedPermanently = false
                                }
                            }
                        },
                        onOpenSettings = { permController.openAppSettings() },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        QrScanner(
                            modifier = Modifier.fillMaxSize(),
                            flashlightOn = false,
                            cameraLens = CameraLens.Back,
                            openImagePicker = false,
                            onCompletion = { scanned ->
                                if (handled) return@QrScanner
                                when (val link = DeepLinkParser.parse(scanned)) {
                                    is DeepLink.Marker -> {
                                        handled = true
                                        navigator.replace(
                                            MarkerScreen(
                                                code = link.code,
                                                scannedValue = link.scannedValue,
                                            )
                                        )
                                    }

                                    is DeepLink.Device -> {
                                        handled = true
                                        navigator.replace(DeviceScreen(slug = link.slug))
                                    }

                                    null -> message = describeUnknown(scanned)
                                }
                            },
                            imagePickerHandler = { },
                            onFailure = { },
                        )
                    }
                }

                message?.let {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFFEF3C7),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = it,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFF92400E),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                TextButton(onClick = { navigator.pop() }) { Text("Cancel") }
            }
        }
    }
}

/**
 * What to say about a code this screen will not act on.
 *
 * Naming the ground-truth grammar specifically, because it is the one a participant
 * will legitimately scan here by mistake — it is the other QR in the same building.
 * "Unknown code" would send them looking for a fault that is not there.
 */
internal fun describeUnknown(scanned: String): String = when {
    scanned.trimStart().startsWith("monad://ground-truth/") ->
        "That is a check-in code, not a quest code. Open Check in from the home screen and " +
            "scan it there — it counts people, which is a different record from a quest."

    else ->
        "That code is not one of ours. Look for a card marked MONAD, or the sticker on the side " +
            "of one of the grey boxes."
}
