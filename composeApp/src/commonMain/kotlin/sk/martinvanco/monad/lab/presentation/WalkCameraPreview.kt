package sk.martinvanco.monad.lab.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Live view of what the tracking camera sees, rendered from the tracker's own session.
 *
 * This is a coaching instrument, not a viewfinder. Walk A (2026-08-19) was carried at a median
 * camera pitch of −39° and tracked 35 % `normal`; walk B at −14° tracked 61 %. The operator was
 * looking at a console while the camera looked at carpet, and nothing on the screen said so. A
 * preview makes the camera's diet visible — and a phone held so the preview shows the room ahead
 * is a phone held the way the tracker needs.
 *
 * [handle] is the platform tracker's [sk.martinvanco.monad.lab.domain.PoseTracker.previewHandle]:
 * the live ARKit session on iOS, null anywhere there is nothing to render. The composable renders
 * nothing when the handle is null — the caller decides what a placeholder says, because "no
 * tracker on this platform" and "tracking has not started" deserve different sentences.
 *
 * The preview never owns the camera. It renders the session the tracker runs; disposing it leaves
 * the session untouched.
 */
@Composable
expect fun WalkCameraPreview(handle: Any?, modifier: Modifier = Modifier)
