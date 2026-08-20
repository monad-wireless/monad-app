package sk.martinvanco.monad.lab.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.ARKit.ARSCNView
import platform.ARKit.ARSession

/**
 * Renders the tracker's live [ARSession] through an [ARSCNView].
 *
 * Three facts hold this together:
 *
 *  - **The view is given the tracker's session, never its own.** The camera is not shareable; an
 *    ARSCNView left to create a session would fight the tracker for the capture pipeline and the
 *    OS would pick a loser. Assigning `session` makes the view a spectator.
 *  - **The view does not become the session's delegate.** ARSCNView renders from the session's
 *    frame stream directly; the `ARSession.delegate` slot stays with the tracker's observer, so
 *    interruption markers keep flowing while the preview is on screen.
 *  - **Disposal leaves the session alone.** The interop releases the view; pausing or re-running
 *    the session is the tracker's job and nobody else's.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun WalkCameraPreview(handle: Any?, modifier: Modifier) {
    val session = handle as? ARSession ?: return
    UIKitView(
        factory = {
            ARSCNView().apply {
                setSession(session)
                // The camera feed is the content; SceneKit statistics and camera controls are
                // debug chrome that would cover it.
                showsStatistics = false
                automaticallyUpdatesLighting = false
            }
        },
        update = { view ->
            // Recomposition with a new session (a new walk without leaving the screen) re-points
            // the view instead of leaking the old spectator.
            if (view.session != session) view.setSession(session)
        },
        modifier = modifier,
    )
}
