package dev.dubec.monad.quests.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A camera over the whole screen, for a step that asks for one printed code.
 *
 * A quest step scrolls, so a camera placed inline below the step's text started half off the
 * screen. [camera] gets the modifier that gives it all the height left between the text at the
 * top and the Close button at the bottom. The caller closes the dialog when the code is read.
 */
@Composable
fun ScannerDialog(
    title: String,
    hint: String?,
    error: String?,
    onClose: () -> Unit,
    camera: @Composable ColumnScope.(Modifier) -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (!hint.isNullOrBlank()) Text(hint, fontSize = 14.sp, color = Color(0xFFCBD5E1))
            camera(Modifier.weight(1f))
            error?.let {
                Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFFFEE2E2), shape = RoundedCornerShape(6.dp)) {
                    Text(it, fontSize = 14.sp, color = Color(0xFF991B1B), modifier = Modifier.padding(12.dp))
                }
            }
            OutlinedButton(
                onClick = onClose,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Color(0xFF94A3B8)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Close camera", fontSize = 15.sp, color = Color.White)
            }
        }
    }
}
