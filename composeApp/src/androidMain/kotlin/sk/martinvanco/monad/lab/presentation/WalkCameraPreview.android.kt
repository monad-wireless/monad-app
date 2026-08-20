package sk.martinvanco.monad.lab.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Nothing to render: the Android build has no pose tracker, so there is never a session to preview.
 * An empty composable rather than a placeholder box — the console's mesh/track panels already say
 * why there is no trajectory on this platform, and a second grey rectangle saying it again is
 * noise.
 */
@Composable
actual fun WalkCameraPreview(handle: Any?, modifier: Modifier) = Unit
