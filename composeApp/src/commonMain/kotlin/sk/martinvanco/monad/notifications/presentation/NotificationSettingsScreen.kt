package sk.martinvanco.monad.notifications.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import sk.martinvanco.monad.core.presentation.components.ScreenWithBackNavigation
import sk.martinvanco.monad.notifications.domain.NotificationPermissionState
import sk.martinvanco.monad.notifications.presentation.settings.NotificationSettingsEvent
import sk.martinvanco.monad.notifications.presentation.settings.NotificationSettingsScreenModel

/**
 * Two toggles and the OS permission, reachable from My account (IP-157).
 *
 * The callouts toggle carries its consent sentence beside it, because App Store Review Guideline
 * 4.5.4 and Google Play's notification policy treat a callout as promotional: it needs an explicit
 * opt-in and an in-app way off, and it defaults to off. The OS state is shown as a fact with the
 * one action that can change it — a first ask, or the system settings page once the OS owns it.
 */
class NotificationSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = koinScreenModel<NotificationSettingsScreenModel>()
        val state by screenModel.state.collectAsState()

        // The permission can change while this screen is not on top (the user goes to system
        // settings and comes back), so re-read it on every composition of the screen root.
        LaunchedEffect(Unit) { screenModel.onResume() }

        ScreenWithBackNavigation(
            title = "Notifications",
            onBackClick = { navigator.pop() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Paper)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Panel {
                    ToggleRow(
                        title = "Project messages",
                        description = "News from the lab: sessions, results, changes to the app.",
                        checked = state.notifyGeneral,
                        enabled = !state.isLoading && !state.isSaving,
                        onChange = { screenModel.onEvent(NotificationSettingsEvent.SetGeneral(it)) },
                    )
                    Divider()
                    ToggleRow(
                        title = "Quest callouts",
                        description = "These are invitations to run a quest when the lab needs a " +
                            "measurement; they are off by default, and you can turn them off any time.",
                        checked = state.notifyCallouts,
                        enabled = !state.isLoading && !state.isSaving,
                        onChange = { screenModel.onEvent(NotificationSettingsEvent.SetCallouts(it)) },
                    )
                }

                if (state.isOffline) {
                    Text(
                        text = "Showing the last saved choices; the server could not be reached.",
                        fontSize = 12.sp,
                        color = Stale,
                    )
                }
                state.error?.let { Text(text = it, fontSize = 12.sp, color = Fault) }

                Panel {
                    PermissionBlock(
                        state = state.permission,
                        onAllow = { screenModel.onEvent(NotificationSettingsEvent.AllowNotifications) },
                        onOpenSettings = { screenModel.onEvent(NotificationSettingsEvent.OpenSystemSettings) },
                    )
                }
            }
        }
    }

    @Composable
    private fun Panel(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            content()
        }
    }

    @Composable
    private fun Divider() {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Paper))
    }

    @Composable
    private fun ToggleRow(
        title: String,
        description: String,
        checked: Boolean,
        enabled: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(description, fontSize = 12.sp, lineHeight = 17.sp, color = Muted)
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Accent),
            )
        }
    }

    @Composable
    private fun PermissionBlock(
        state: NotificationPermissionState,
        onAllow: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Phone permission", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when (state) {
                        NotificationPermissionState.GRANTED -> "Granted"
                        NotificationPermissionState.DENIED -> "Denied"
                        NotificationPermissionState.NOT_ASKED -> "Not asked yet"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (state) {
                        NotificationPermissionState.GRANTED -> Good
                        NotificationPermissionState.DENIED -> Fault
                        NotificationPermissionState.NOT_ASKED -> Muted
                    },
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = when (state) {
                        NotificationPermissionState.GRANTED -> "The phone will show what you switched on above."
                        NotificationPermissionState.DENIED -> "Nothing arrives until you allow it in system settings."
                        NotificationPermissionState.NOT_ASKED -> "Nothing arrives until you allow it."
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
            }
            when (state) {
                NotificationPermissionState.NOT_ASKED -> Button(
                    onClick = onAllow,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow notifications", color = Color.White, fontWeight = FontWeight.SemiBold)
                }

                else -> OutlinedButton(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open system settings", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private val Accent = Color(0xFF5B6ECC)
private val Ink = Color(0xFF0F142F)
private val Muted = Color(0xFF64748B)
private val Paper = Color(0xFFF1F5F9)
private val Stale = Color(0xFF92400E)
private val Fault = Color(0xFFDC2626)
private val Good = Color(0xFF166534)
