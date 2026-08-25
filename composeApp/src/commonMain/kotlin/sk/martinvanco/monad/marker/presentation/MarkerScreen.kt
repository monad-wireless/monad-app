package sk.martinvanco.monad.marker.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.koin.core.parameter.parametersOf
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.quests.presentation.active_quest.ActiveQuestScreen

/**
 * Where a scanned marker card lands in the app (IP-140).
 *
 * Mostly a spinner, and that is the design. A participant standing at a card wants the countdown,
 * not a screen — so the common path is resolve, start, and replace this screen with the running
 * quest, with the code they just scanned already satisfying its first probe. They never see this.
 *
 * What they do see is every case where that cannot happen: not signed in, nothing accepts the card,
 * more than one thing does, or the server is unreachable. Each of those gets a sentence naming what
 * to do, because the alternative — a participant holding a working card being told nothing — is the
 * failure the `/m/<CODE>` portal page was written to catch and could not, since until now the app
 * did not claim the path at all.
 */
data class MarkerScreen(
    val code: String,
    val scannedValue: String,
) : Screen {

    override val key: String get() = "marker-$code"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = getScreenModel<MarkerScreenModel> { parametersOf(code, scannedValue) }
        val state by screenModel.state.collectAsState()

        // Replace rather than push: this screen is a router, and leaving it on the back stack would
        // let a participant walk backwards out of a running quest into a resolver for a card they
        // have already used.
        LaunchedEffect(state.startedQuestId) {
            val questId = state.startedQuestId ?: return@LaunchedEffect
            navigator.replaceAll(
                ActiveQuestScreen(questId = questId, preScannedValue = scannedValue),
            )
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                state.resolving || state.starting -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (state.starting) "Starting your run…" else "Reading $code…",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.signInRequired -> MarkerMessage(
                    title = "Sign in to use this card",
                    body = "You scanned $code. Runs are recorded against an account, so the " +
                        "measurement can be attributed and so you can be told what it produced.",
                    actionLabel = "Sign in",
                    onAction = { navigator.push(LoginScreen()) },
                )

                state.candidates.isNotEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "More than one run uses $code",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        // Not a preference. Which quest a walk belongs to decides which experiment
                        // its dwells are filed under, and nothing downstream can undo a wrong guess.
                        text = "Pick the one the operator asked you to run.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.candidates.forEach { quest ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 2.dp,
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(quest.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                if (quest.description.isNotBlank()) {
                                    Text(
                                        text = quest.description,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(onClick = { screenModel.choose(quest.id) }) { Text("Start this run") }
                            }
                        }
                    }
                }

                else -> MarkerMessage(
                    title = "This card is not in use",
                    body = state.error.orEmpty(),
                    actionLabel = "Try again",
                    onAction = { screenModel.retry() },
                )
            }
        }
    }
}

@Composable
private fun MarkerMessage(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(actionLabel) }
    }
}
