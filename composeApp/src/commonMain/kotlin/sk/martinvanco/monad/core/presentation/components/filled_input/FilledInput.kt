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
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
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
    contentType: ContentType? = null,
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

    // Use label as placeholder if no placeholder is provided
    val finalPlaceholder = placeholder.ifEmpty { label }

    // What a password manager is allowed to put here.
    //
    // A field with no `contentType` is not invisible to autofill — the provider falls back to
    // guessing from whatever it can see. That guess is unreliable in Compose: `label = null`
    // above removes the animated label, so the only text near the field is a placeholder that
    // disappears the moment somebody types. Declaring the type is what turns a guess into a
    // contract, and it is the one thing that makes the save prompt offer the right pair.
    //
    // Android reads this as an autofill hint (`AUTOFILL_HINT_USERNAME` and friends). iOS ignores
    // it in Compose Multiplatform 1.8.2 — `ContentType.skiko.kt` is a stub carrying JetBrains'
    // own `TODO CMP-7154 Adopt Autofill semantic properties` — and derives `UITextContentType`
    // from `keyboardType` instead, which is why the login fields also keep KeyboardType.Email
    // and KeyboardType.Password. Setting both is not belt and braces: it is one declaration per
    // platform, and dropping either loses autofill on that platform alone.
    val autofillModifier = if (contentType == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentType = contentType }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().then(autofillModifier),
            enabled = enabled,
            label = null, // Remove animated label
            placeholder = if (finalPlaceholder.isNotEmpty()) {
                { Text(text = finalPlaceholder) }
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
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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