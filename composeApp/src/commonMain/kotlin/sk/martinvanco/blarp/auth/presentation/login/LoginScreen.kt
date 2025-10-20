package sk.martinvanco.blarp.auth.presentation.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import blarp_app.composeapp.generated.resources.Res
import blarp_app.composeapp.generated.resources.field_label_email
import blarp_app.composeapp.generated.resources.field_label_password
import blarp_app.composeapp.generated.resources.login_screen
import blarp_app.composeapp.generated.resources.login_screen_forgot_password
import blarp_app.composeapp.generated.resources.login_screen_login
import blarp_app.composeapp.generated.resources.login_screen_no_acc
import blarp_app.composeapp.generated.resources.monad_logo_dark
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import sk.martinvanco.blarp.core.presentation.components.button_primary.ButtonPrimary
import sk.martinvanco.blarp.core.presentation.components.filled_input.FilledInput
import sk.martinvanco.blarp.core.util.dismissKeyboardOnTap
import sk.martinvanco.blarp.ui.theme.h1

class LoginScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<LoginScreenModel>()
        val state by screenModel.state.collectAsState()

        Box(
            modifier = Modifier
                .fillMaxSize()
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
                    text = stringResource(Res.string.login_screen),
                    style = MaterialTheme.typography.h1,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                FilledInput(
                    value = state.email,
                    onValueChange = { screenModel.onEvent(LoginEvent.OnEmailFieldChange(it)) },
                    label = stringResource(Res.string.field_label_email),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                FilledInput(
                    value = state.password,
                    onValueChange = { screenModel.onEvent(LoginEvent.OnPasswordFieldChange(it)) },
                    label = stringResource(Res.string.field_label_password),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { screenModel.onEvent(LoginEvent.OnLoginButtonClick) }
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = { screenModel.onEvent(LoginEvent.OnForgotPasswordClick) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = stringResource(Res.string.login_screen_forgot_password),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                ButtonPrimary(
                    text = stringResource(Res.string.login_screen_login),
                    onClick = { screenModel.onEvent(LoginEvent.OnLoginButtonClick) },
                    isLoading = false
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = { screenModel.onEvent(LoginEvent.OnCreateAccountButtonClick) }
                ) {
                    Text(text = stringResource(Res.string.login_screen_no_acc))
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
