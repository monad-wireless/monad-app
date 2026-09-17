package sk.martinvanco.monad.notifications.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import sk.martinvanco.monad.core.deeplink.DeepLink
import sk.martinvanco.monad.core.presentation.components.ScreenWithBackNavigation
import sk.martinvanco.monad.device.presentation.DeviceScreen
import sk.martinvanco.monad.marker.presentation.MarkerScreen
import sk.martinvanco.monad.notifications.domain.InboxItem
import sk.martinvanco.monad.notifications.domain.InboxSection
import sk.martinvanco.monad.notifications.domain.NotificationType
import sk.martinvanco.monad.quests.presentation.quest_detail.QuestDetailScreen

/**
 * The inbox (IP-157): every notification the lab sent this account, grouped by day, newest first.
 *
 * Unread rows are bold and carry a dot. Tapping marks the row read; a `quest_callout` with a quest
 * opens `QuestDetailScreen`, a message with a printed-grammar `deep_link` goes where the sticker
 * would, and anything else stays put. The rows come from the process-wide inbox, so the badge on
 * the top bar and this list can never disagree.
 */
class NotificationsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = koinScreenModel<NotificationsScreenModel>()
        val state by screenModel.state.collectAsState()

        LaunchedEffect(state.destination) {
            when (val destination = state.destination) {
                null -> Unit
                is InboxDestination.Quest -> navigator.push(QuestDetailScreen(destination.questId))
                is InboxDestination.Link -> when (val link = destination.link) {
                    is DeepLink.Device -> navigator.push(DeviceScreen(slug = link.slug, questId = link.questId))
                    is DeepLink.Marker -> navigator.push(MarkerScreen(code = link.code, scannedValue = link.scannedValue))
                }
            }
            if (state.destination != null) screenModel.onEvent(NotificationsEvent.DestinationHandled)
        }

        ScreenWithBackNavigation(
            title = "Notifications",
            onBackClick = { navigator.pop() },
        ) {
            Column(modifier = Modifier.fillMaxSize().background(Paper)) {
                Toolbar(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { screenModel.onEvent(NotificationsEvent.Refresh) },
                    onOpenSettings = { navigator.push(NotificationSettingsScreen()) },
                )

                state.error?.let { error ->
                    Text(
                        text = error,
                        fontSize = 12.sp,
                        color = Stale,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                when {
                    state.isEmpty && state.isRefreshing -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Accent)
                    }

                    state.isEmpty -> EmptyState()

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.sections.forEach { section ->
                            item(key = "day-${section.day}") { DayHeader(section) }
                            items(section.items, key = { it.id }) { item ->
                                NotificationRow(
                                    item = item,
                                    onClick = { screenModel.onEvent(NotificationsEvent.Open(item.id)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Toolbar(isRefreshing: Boolean, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Notification settings", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            if (isRefreshing) {
                CircularProgressIndicator(
                    color = Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(end = 12.dp).size(18.dp),
                )
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Accent)
                }
            }
        }
    }

    @Composable
    private fun DayHeader(section: InboxSection) {
        Text(
            text = section.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Muted,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
    }

    @Composable
    private fun NotificationRow(item: InboxItem, onClick: () -> Unit) {
        val zone = TimeZone.currentSystemDefault()
        val time = item.sentAt.toLocalDateTime(zone)
        val clock = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
        val weight = if (item.isUnread) FontWeight.Bold else FontWeight.Normal

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .clickable(onClick = onClick)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (item.isUnread) Accent else Color.Transparent),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.title,
                        fontSize = 15.sp,
                        fontWeight = weight,
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = clock, fontSize = 12.sp, color = Muted)
                }
                Text(
                    text = item.body,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = weight,
                    color = if (item.isUnread) Ink else Muted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.type == NotificationType.QUEST_CALLOUT) {
                    Text(
                        text = if (item.opensQuestId != null) "Quest callout · tap to open the quest" else "Quest callout",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Accent,
                    )
                }
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "Nothing yet: messages from the lab and quest callouts will arrive here.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val Accent = Color(0xFF5B6ECC)
private val Ink = Color(0xFF0F142F)
private val Muted = Color(0xFF64748B)
private val Paper = Color(0xFFF1F5F9)
private val Stale = Color(0xFF92400E)
