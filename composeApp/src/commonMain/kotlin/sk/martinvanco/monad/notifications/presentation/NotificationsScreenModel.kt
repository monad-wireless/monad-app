package sk.martinvanco.monad.notifications.presentation

import cafe.adriel.voyager.core.model.StateScreenModel

class NotificationsScreenModel : StateScreenModel<NotificationsState>(NotificationsState()) {

    fun onEvent(event: NotificationsEvent) {
        // TODO: Implement event handling
    }
}
