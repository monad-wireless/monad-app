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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import monad.composeapp.generated.resources.Res
import monad.composeapp.generated.resources.monad_logo_dark
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import org.jetbrains.compose.resources.painterResource
import sk.martinvanco.monad.core.presentation.components.button_primary.ButtonPrimary
import sk.martinvanco.monad.core.presentation.components.filled_input.FilledInput
import sk.martinvanco.monad.core.util.dismissKeyboardOnTap
import sk.martinvanco.monad.ui.theme.h1

class LoginScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = koinScreenModel<LoginScreenModel>()
        val state by screenModel.state.collectAsState()
        val keyboardController = LocalSoftwareKeyboardController.current

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
                    text = "Welcome to MONAD",
                    style = MaterialTheme.typography.h1,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter your nickname to continue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                FilledInput(
                    value = state.nickname,
                    onValueChange = { screenModel.onEvent(LoginEvent.OnNicknameFieldChange(it)) },
                    label = "Nickname",
                    errorText = state.nicknameError ?: "",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.None
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            screenModel.onEvent(LoginEvent.ContinueButtonClick)
                        }
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                ButtonPrimary(
                    text = "Continue",
                    onClick = { screenModel.onEvent(LoginEvent.ContinueButtonClick) },
                    isLoading = state.isLoading
                )
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
