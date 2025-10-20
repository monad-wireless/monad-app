package sk.martinvanco.monad.core.util

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Modifier that hides the keyboard and clears focus when tapping outside of input fields
 */
@Composable
fun Modifier.dismissKeyboardOnTap(): Modifier {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    return this.pointerInput(Unit) {
        detectTapGestures(
            onTap = {
                keyboardController?.hide()
                focusManager.clearFocus()
            }
        )
    }
}
