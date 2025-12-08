package sk.martinvanco.monad.auth.presentation.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import monad.composeapp.generated.resources.Res
import monad.composeapp.generated.resources.field_label_email
import monad.composeapp.generated.resources.field_label_password
import monad.composeapp.generated.resources.login_screen
import monad.composeapp.generated.resources.login_screen_forgot_password
import monad.composeapp.generated.resources.login_screen_login
import monad.composeapp.generated.resources.login_screen_no_acc
import monad.composeapp.generated.resources.monad_logo_dark
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sk.martinvanco.monad.core.presentation.components.button_primary.ButtonPrimary
import sk.martinvanco.monad.core.presentation.components.filled_input.FilledInput
import sk.martinvanco.monad.core.util.dismissKeyboardOnTap
import sk.martinvanco.monad.ui.theme.h1

class LoginScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<LoginScreenModel>()
        val state by screenModel.state.collectAsState()

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
                    text = stringResource(Res.string.login_screen),
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
                    value = state.email,
                    onValueChange = { screenModel.onEvent(LoginEvent.OnEmailFieldChange(it)) },
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
                    onValueChange = { screenModel.onEvent(LoginEvent.OnPasswordFieldChange(it)) },
                    label = stringResource(Res.string.field_label_password),
                    errorText = state.passwordError ?: "",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { screenModel.onEvent(LoginEvent.LoginButtonClick) }
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = { screenModel.onEvent(LoginEvent.ForgotPasswordClick) },
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
                    onClick = { screenModel.onEvent(LoginEvent.LoginButtonClick) },
                    isLoading = state.isLoading
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = { screenModel.onEvent(LoginEvent.CreateAccountButtonClick) }
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
