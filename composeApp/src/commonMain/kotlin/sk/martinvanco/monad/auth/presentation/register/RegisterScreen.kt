package sk.martinvanco.monad.auth.presentation.register

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import monad.composeapp.generated.resources.Res
import monad.composeapp.generated.resources.create_account_screen
import monad.composeapp.generated.resources.create_account_screen_already_have_acc
import monad.composeapp.generated.resources.field_label_email
import monad.composeapp.generated.resources.field_label_name
import monad.composeapp.generated.resources.field_label_password
import monad.composeapp.generated.resources.field_label_repeat_password
import monad.composeapp.generated.resources.monad_logo_dark
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sk.martinvanco.monad.core.config.AppConfig
import sk.martinvanco.monad.core.presentation.components.button_primary.ButtonPrimary
import sk.martinvanco.monad.core.presentation.components.filled_input.FilledInput
import sk.martinvanco.monad.core.util.dismissKeyboardOnTap
import sk.martinvanco.monad.ui.theme.h1

class RegisterScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<RegisterScreenModel>()
        val state by screenModel.state.collectAsState()
        val keyboardController = LocalSoftwareKeyboardController.current
        val uriHandler = LocalUriHandler.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(24.dp)
                .dismissKeyboardOnTap()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(Res.string.create_account_screen),
                    style = MaterialTheme.typography.h1,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                FilledInput(
                    value = state.name,
                    onValueChange = { screenModel.onEvent(RegisterEvent.OnNameFieldChange(it)) },
                    label = stringResource(Res.string.field_label_name),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                FilledInput(
                    value = state.email,
                    onValueChange = { screenModel.onEvent(RegisterEvent.OnEmailFieldChange(it)) },
                    label = stringResource(Res.string.field_label_email),
                    errorText = state.emailError ?: "",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                FilledInput(
                    value = state.password,
                    onValueChange = { screenModel.onEvent(RegisterEvent.OnPasswordFieldChange(it)) },
                    label = stringResource(Res.string.field_label_password),
                    errorText = state.passwordError ?: "",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                FilledInput(
                    value = state.repeatPassword,
                    onValueChange = { screenModel.onEvent(RegisterEvent.OnRepeatPasswordFieldChange(it)) },
                    label = stringResource(Res.string.field_label_repeat_password),
                    errorText = state.repeatPasswordError ?: "",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            screenModel.onEvent(RegisterEvent.CreateAccountButtonClick)
                        }
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.termsAccepted,
                        onCheckedChange = { screenModel.onEvent(RegisterEvent.OnTermsAcceptedChange(it)) }
                    )

                    val linkColor = MaterialTheme.colorScheme.primary
                    val textColor = MaterialTheme.colorScheme.onSurface

                    val annotatedText = buildAnnotatedString {
                        withStyle(SpanStyle(color = textColor)) {
                            append("I agree to the ")
                        }
                        pushStringAnnotation(tag = "URL", annotation = "${AppConfig.BASE_URL}/terms")
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append("Terms & Conditions")
                        }
                        pop()
                        withStyle(SpanStyle(color = textColor)) {
                            append(" and ")
                        }
                        pushStringAnnotation(tag = "URL", annotation = "${AppConfig.BASE_URL}/privacy-policy")
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append("Privacy Policy")
                        }
                        pop()
                    }

                    ClickableText(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodySmall,
                        onClick = { offset ->
                            annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    uriHandler.openUri(annotation.item)
                                }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                ButtonPrimary(
                    text = stringResource(Res.string.create_account_screen),
                    onClick = { screenModel.onEvent(RegisterEvent.CreateAccountButtonClick) },
                    isLoading = state.isLoading
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = { screenModel.onEvent(RegisterEvent.LoginButtonClick) }
                ) {
                    Text(text = stringResource(Res.string.create_account_screen_already_have_acc))
                }
            }

            // Logo at the bottom
            Image(
                painter = painterResource(Res.drawable.monad_logo_dark),
                contentDescription = "Monad Logo",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(91.dp)
                    .height(30.dp)
            )
        }
    }
}
