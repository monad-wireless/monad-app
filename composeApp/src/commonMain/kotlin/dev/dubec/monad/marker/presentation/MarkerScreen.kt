package dev.dubec.monad.marker.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.koin.core.parameter.parametersOf
import dev.dubec.monad.auth.presentation.login.LoginScreen
import dev.dubec.monad.core.presentation.components.ScreenWithBackNavigation
import dev.dubec.monad.main.presentation.MainContainerScreen
import dev.dubec.monad.quests.presentation.active_quest.ActiveQuestScreen
import dev.dubec.monad.quests.presentation.quest_detail.QuestDetailScreen

/**
 * Where a marker card scanned outside a quest lands in the app (IP-140, changed 2026-11-25).
 *
 * The card leads to the quests that use it, and the participant starts one from its own page. It no
 * longer starts a run by itself: a run nobody chose is a record nobody can interpret. A card whose
 * quest is already open on this phone leads back to that quest.
 */
data class MarkerScreen(
    val code: String,
    val scannedValue: String,
) : Screen {

    override val key: String get() = "marker-$code"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = getScreenModel<MarkerScreenModel> { parametersOf(scannedValue) }
        val state by screenModel.state.collectAsState()

        // Replace rather than push: this screen is a router, and Back from the quest page should
        // lead home and not to a resolver for a card that has already been read.
        LaunchedEffect(state.singleQuestId) {
            state.singleQuestId?.let { navigator.replace(QuestDetailScreen(it)) }
        }

        ScreenWithBackNavigation(title = "Measurement point", onBackClick = { navigator.leave() }) {
            when {
                state.resolving || state.singleQuestId != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Accent)
                }

                state.signInRequired -> MarkerMessage(
                    title = "Please sign in first",
                    body = "Each measurement belongs to an account, so you need to sign in before you " +
                        "can start a quest.",
                    actionLabel = "Sign in",
                    onAction = { navigator.push(LoginScreen()) },
                )

                state.openQuestId != null -> MarkerMessage(
                    title = "A quest is already open",
                    body = "Please finish that quest first. To record this point, use the scanner " +
                        "inside the quest.",
                    actionLabel = "Back to the quest",
                    onAction = { navigator.returnToQuest(state.openQuestId.orEmpty()) },
                )

                state.candidates.isNotEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Quests at this point",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        text = "This card marks a place whose position we measured. Choose a quest to " +
                            "read what it involves.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = Muted,
                    )
                    state.candidates.forEach { quest ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFFF1F5F9))
                                .clickable { navigator.replace(QuestDetailScreen(quest.id)) }
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(quest.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                            if (quest.description.isNotBlank()) {
                                Text(
                                    text = quest.description,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    color = Muted,
                                )
                            }
                        }
                    }
                }

                state.unreachable -> MarkerMessage(
                    title = "No connection",
                    body = "The list of quests could not be loaded. Please check your connection.",
                    actionLabel = "Try again",
                    onAction = { screenModel.retry() },
                )

                else -> MarkerMessage(
                    title = "No quest uses this point now",
                    body = "The card belongs to the study, but no open quest needs it at the moment.",
                    actionLabel = "See the quests",
                    onAction = { navigator.replaceAll(MainContainerScreen()) },
                    secondaryLabel = "Try again",
                    onSecondary = { screenModel.retry() },
                )
            }
        }
    }
}

/** Back, or home when a link opened the app on this screen and nothing lies below it. */
private fun Navigator.leave() {
    if (!pop()) replaceAll(MainContainerScreen())
}

/** The running quest lies below this screen on a warm app. Only a cold one has to open it again. */
private fun Navigator.returnToQuest(questId: String) {
    val below = items.dropLast(1).lastOrNull()
    if (below is ActiveQuestScreen) pop() else replaceAll(ActiveQuestScreen(questId))
}

@Composable
private fun MarkerMessage(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
        Text(
            text = body,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            shape = RoundedCornerShape(12.dp),
        ) { Text(actionLabel, color = Color.White, fontWeight = FontWeight.SemiBold) }
        if (secondaryLabel != null) {
            TextButton(onClick = onSecondary) { Text(secondaryLabel, color = Accent) }
        }
    }
}

private val Accent = Color(0xFF5B6ECC)
private val Ink = Color(0xFF0F142F)
private val Muted = Color(0xFF64748B)
