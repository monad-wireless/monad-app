package sk.martinvanco.monad.device.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.koin.core.parameter.parametersOf
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.device.data.dto.AvailabilityDto
import sk.martinvanco.monad.device.data.dto.DeviceQuestDto
import sk.martinvanco.monad.quests.presentation.quest_detail.QuestDetailScreen

/**
 * Where a scanned device label lands in the app (IP-128).
 *
 * The screen a person sees while physically standing next to the box, so it
 * answers "what is this, and can I do something here" and nothing else.
 *
 * **Guests are read-only by decision.** A signed-out visitor sees the same node
 * and the same quests as anyone else; only the button changes. The account ask
 * is the last thing on the screen, never a wall in front of it — the reason the
 * whole flow exists is that someone pointed a camera at an unfamiliar object,
 * and demanding registration before explaining the object wastes the moment.
 *
 * @param slug fleet identity from the URL, e.g. `monad04`
 * @param questId optional `?q=`; an unknown or expired id degrades to the full
 *   device view rather than an error, because the sticker outlives the quest.
 */
data class DeviceScreen(
    val slug: String,
    val questId: String? = null,
) : Screen {

    override val key: String get() = "device-$slug-${questId ?: ""}"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = getScreenModel<DeviceScreenModel> { parametersOf(slug, questId) }
        val state by screenModel.state.collectAsState()

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.error != null -> DeviceError(
                    message = state.error.orEmpty(),
                    onRetry = { screenModel.onEvent(DeviceEvent.Retry) },
                )

                else -> DeviceContent(
                    state = state,
                    onOpenQuest = { navigator.push(QuestDetailScreen(it.id)) },
                    onSignIn = { navigator.push(LoginScreen()) },
                )
            }
        }
    }
}

@Composable
private fun DeviceError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun DeviceContent(
    state: DeviceState,
    onOpenQuest: (DeviceQuestDto) -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text(
            text = state.slug.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        state.detail?.device?.location?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        state.detail?.device?.publicBlurb?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }

        if (state.isRetired) {
            Spacer(Modifier.height(16.dp))
            Text(
                // The sticker is still on a wall somewhere; saying "retired" is
                // more useful than a 404 that reads as "you scanned something broken".
                text = "This node has been retired from the fleet. Its label is still out there — this is what it used to do.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        // A deep link may name one quest; lead with it, then the rest.
        val focus = state.focusQuest
        val rest = state.quests.filter { it.id != focus?.id }

        if (state.quests.isEmpty()) {
            Text("Nothing to run at this node right now.", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                text = "What you can do here",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            focus?.let {
                QuestRow(it, state.isSignedIn, onOpenQuest, onSignIn)
                Spacer(Modifier.height(12.dp))
            }
            rest.forEach {
                QuestRow(it, state.isSignedIn, onOpenQuest, onSignIn)
                Spacer(Modifier.height(12.dp))
            }
        }

        if (!state.isSignedIn && state.quests.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                // The ask, last and scoped: what an account is FOR, not a demand.
                text = "You can look around without an account. Signing in lets your walks count toward the research — and collects this node in your passport.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun QuestRow(
    quest: DeviceQuestDto,
    isSignedIn: Boolean,
    onOpenQuest: (DeviceQuestDto) -> Unit,
    onSignIn: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(quest.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (quest.description.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(quest.description, style = MaterialTheme.typography.bodyMedium)
        }

        val blocked = blockedReason(quest.availability)
        Spacer(Modifier.height(8.dp))
        when {
            blocked != null -> Text(blocked, style = MaterialTheme.typography.bodySmall)
            isSignedIn -> Button(onClick = { onOpenQuest(quest) }) { Text("Start") }
            // Read-only guest: the quest is visible and explained, and the wall
            // is the button rather than the screen.
            else -> OutlinedButton(onClick = onSignIn) { Text("Sign in to run this") }
        }
    }
}

/**
 * Turns an availability reason into something worth reading while standing in a
 * corridor. `null` means runnable.
 *
 * `node_idle` is the one that matters most: the fleet only captures during a
 * scheduled session, so most of the day most nodes are resting. Saying so is
 * honest and sets an expectation to come back — hiding it would mean awarding a
 * stamp for a run that recorded nothing.
 */
private fun blockedReason(availability: AvailabilityDto): String? {
    if (availability.available) return null
    return when (availability.reason) {
        AvailabilityDto.REASON_NODE_IDLE ->
            "This node is resting — it only records during a scheduled session. Worth another scan later."
        AvailabilityDto.REASON_COOLDOWN ->
            "You have already run this here recently. It opens up again after a short rest."
        AvailabilityDto.REASON_IN_PROGRESS ->
            "You have a run of this already in progress."
        AvailabilityDto.REASON_DEVICE_INACTIVE ->
            "This node is out of service."
        AvailabilityDto.REASON_WINDOW_CLOSED ->
            "This one is not running at the moment."
        else -> "Not available here right now."
    }
}
