package sk.martinvanco.monad.core.presentation.components.filled_input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun FilledInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    errorText: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    showKeyboardDismissAction: Boolean = false,
) {
    val isError = errorText.isNotEmpty()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Configure keyboard options with dismiss action if requested
    val finalKeyboardOptions = if (showKeyboardDismissAction && keyboardOptions.imeAction == ImeAction.Default) {
        keyboardOptions.copy(imeAction = ImeAction.Done)
    } else {
        keyboardOptions
    }

    // Add keyboard dismiss action for Done if requested
    val finalKeyboardActions = if (showKeyboardDismissAction &&
        keyboardActions == KeyboardActions.Default &&
        finalKeyboardOptions.imeAction == ImeAction.Done
    ) {
        KeyboardActions(
            onDone = { keyboardController?.hide() }
        )
    } else {
        keyboardActions
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = if (label.isNotEmpty()) {
                { Text(text = label) }
            } else null,
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(text = placeholder) }
            } else null,
            singleLine = singleLine,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = finalKeyboardOptions,
            keyboardActions = finalKeyboardActions,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.secondary,
                unfocusedTextColor = MaterialTheme.colorScheme.secondary,
                disabledTextColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.38f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = MaterialTheme.colorScheme.error,
                cursorColor = MaterialTheme.colorScheme.secondary,
                focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurface,
                errorLabelColor = MaterialTheme.colorScheme.error,
            ),
            textStyle = MaterialTheme.typography.bodyLarge,
        )

        if (isError) {
            Text(
                text = errorText,
                modifier = Modifier.padding(start = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}