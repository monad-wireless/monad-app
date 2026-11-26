package dev.dubec.monad.quests.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dubec.monad.core.presentation.components.PermissionRequiredCard
import dev.icerock.moko.permissions.DeniedAlwaysException
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.camera.CAMERA
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import qrscanner.CameraLens
import qrscanner.QrScanner

/**
 * Reads one printed code, then goes quiet.
 *
 * It opens a camera only when nothing else holds it. While a walk is tracked the pose tracker owns
 * the rear camera, and a second capture session kills the pose stream (see `ProbeStep` and
 * `CameraOwnershipTest`); then the code comes from the tracker's own frames through [seenCard],
 * and it must survive one further decode, so a card passed at two metres is not a scan.
 */
@Composable
fun CardScanner(
    trackerOwnsCamera: Boolean,
    seenCard: String?,
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    // true: the camera takes all the room [modifier] gives it (a full-screen scanner).
    // false: a 280 dp strip inside a scrolling step.
    fill: Boolean = false,
) {
    val factory = rememberPermissionsControllerFactory()
    val permissions = remember(factory) { factory.createPermissionsController() }
    BindEffect(permissions)
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf<Boolean?>(null) }
    var deniedForever by remember { mutableStateOf(false) }
    // The scanner fires for as long as a code is in frame.
    var read by remember { mutableStateOf(false) }
    val currentOnCode by rememberUpdatedState(onCode)

    fun accept(code: String) {
        if (read || code.isBlank()) return
        read = true
        currentOnCode(code)
    }

    LaunchedEffect(permissions) { granted = permissions.isPermissionGranted(Permission.CAMERA) }

    if (trackerOwnsCamera) {
        LaunchedEffect(seenCard) {
            val code = seenCard ?: return@LaunchedEffect
            delay(600)
            accept(code) // the effect restarts when the code changes, so this is a second look
        }
        Text(
            text = seenCard?.let { "Reading $it. Hold it there." } ?: "Point the phone at the code on the card.",
            fontSize = 13.sp,
            color = Color(0xFF64748B),
            modifier = modifier,
        )
        return
    }

    when (granted) {
        null -> Unit
        false -> PermissionRequiredCard(
            permissionName = "Camera",
            deniedPermanently = deniedForever,
            onRequestPermission = {
                scope.launch {
                    try {
                        permissions.providePermission(Permission.CAMERA)
                        granted = true
                    } catch (e: DeniedAlwaysException) {
                        deniedForever = true
                    } catch (e: DeniedException) {
                        // Denied once; the card stays.
                    } catch (e: Exception) {
                        deniedForever = false
                    }
                }
            },
            onOpenSettings = { permissions.openAppSettings() },
        )

        true -> Box(
            modifier = (if (fill) modifier.fillMaxSize() else modifier.fillMaxWidth().height(280.dp))
                .clip(RoundedCornerShape(if (fill) 12.dp else 8.dp)),
        ) {
            QrScanner(
                modifier = Modifier.fillMaxSize(),
                flashlightOn = false,
                cameraLens = CameraLens.Back,
                openImagePicker = false,
                onCompletion = { accept(it) },
                imagePickerHandler = { /* Not used */ },
                onFailure = { /* Per frame; nothing to say. */ },
            )
        }
    }
}
