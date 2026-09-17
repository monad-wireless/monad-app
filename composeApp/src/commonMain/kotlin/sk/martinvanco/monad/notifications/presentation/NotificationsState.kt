package sk.martinvanco.monad.notifications.presentation

import sk.martinvanco.monad.core.deeplink.DeepLink
import sk.martinvanco.monad.notifications.domain.InboxSection

/** Where a tapped row wants to go. Consumed once by the screen, then cleared. */
sealed interface InboxDestination {
    data class Quest(val questId: String) : InboxDestination
    data class Link(val link: DeepLink) : InboxDestination
}

data class NotificationsState(
    val sections: List<InboxSection> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val destination: InboxDestination? = null,
) {
    val isEmpty: Boolean get() = sections.isEmpty()
}
