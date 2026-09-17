package sk.martinvanco.monad.notifications.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import sk.martinvanco.monad.core.deeplink.DeepLinkParser
import sk.martinvanco.monad.core.util.currentTimeMillis
import kotlinx.datetime.Instant
import sk.martinvanco.monad.notifications.data.NotificationInbox
import sk.martinvanco.monad.notifications.domain.InboxGrouping

/**
 * The inbox list. Reads the process-wide [NotificationInbox]; owns nothing the badge does not see.
 */
class NotificationsScreenModel(
    private val inbox: NotificationInbox,
) : StateScreenModel<NotificationsState>(NotificationsState()) {

    init {
        combine(inbox.items, inbox.isRefreshing, inbox.error) { items, refreshing, error ->
            val zone = TimeZone.currentSystemDefault()
            val today = Instant.fromEpochMilliseconds(currentTimeMillis()).toLocalDateTime(zone).date
            Triple(InboxGrouping.groupByDay(items, today, zone), refreshing, error)
        }
            .onEach { (sections, refreshing, error) ->
                mutableState.value = mutableState.value.copy(
                    sections = sections,
                    isRefreshing = refreshing,
                    error = error,
                )
            }
            .launchIn(screenModelScope)

        screenModelScope.launch { inbox.refresh() }
    }

    fun onEvent(event: NotificationsEvent) {
        when (event) {
            NotificationsEvent.Refresh -> screenModelScope.launch { inbox.refresh() }

            is NotificationsEvent.Open -> screenModelScope.launch {
                val item = inbox.items.value.firstOrNull { it.id == event.id } ?: return@launch
                inbox.markRead(item.id)
                val destination = item.opensQuestId?.let { InboxDestination.Quest(it) }
                    ?: DeepLinkParser.parse(item.deepLink)?.let { InboxDestination.Link(it) }
                if (destination != null) {
                    mutableState.value = mutableState.value.copy(destination = destination)
                }
            }

            NotificationsEvent.DestinationHandled ->
                mutableState.value = mutableState.value.copy(destination = null)
        }
    }
}
