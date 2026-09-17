package sk.martinvanco.monad.notifications.presentation

sealed interface NotificationsEvent {
    data object Refresh : NotificationsEvent

    /** A row was tapped: mark it read and, for a callout or a link, say where to go. */
    data class Open(val id: String) : NotificationsEvent

    /** The screen navigated; forget the destination so a recomposition cannot repeat it. */
    data object DestinationHandled : NotificationsEvent
}
