package sk.martinvanco.monad.quests.presentation.quest_completed

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.core.data.repository.SettingsRepository
import sk.martinvanco.monad.notifications.data.NotificationPermissionGate
import sk.martinvanco.monad.notifications.domain.NotificationPermissionState

data class QuestCompletedState(
    /** The pre-prompt card. Shown once, after the first completed quest, never on launch. */
    val showNotificationPrompt: Boolean = false,
)

sealed interface QuestCompletedEvent {
    data object AllowNotifications : QuestCompletedEvent
    data object NotNow : QuestCompletedEvent
}

/**
 * The permission moment (IP-157).
 *
 * The first `QuestCompletedScreen` is the point where a participant has just done the thing a
 * callout would ask them to do again, so "want to know when the lab needs a measurement?" is a
 * question they can answer. The card appears only if the OS has never been asked, and the
 * `quest_completed_seen` flag is written on first show so a second completion never repeats it.
 */
class QuestCompletedScreenModel(
    private val settings: SettingsRepository,
    private val gate: NotificationPermissionGate,
) : StateScreenModel<QuestCompletedState>(QuestCompletedState()) {

    init {
        screenModelScope.launch {
            val seen = settings.getSetting(SettingsRepository.KEY_QUEST_COMPLETED_SEEN) == "true"
            if (seen) return@launch
            settings.setSetting(SettingsRepository.KEY_QUEST_COMPLETED_SEEN, "true")
            if (gate.state() == NotificationPermissionState.NOT_ASKED) {
                mutableState.value = mutableState.value.copy(showNotificationPrompt = true)
            }
        }
    }

    fun onEvent(event: QuestCompletedEvent) {
        when (event) {
            QuestCompletedEvent.AllowNotifications -> screenModelScope.launch {
                mutableState.value = mutableState.value.copy(showNotificationPrompt = false)
                gate.request()
            }

            QuestCompletedEvent.NotNow ->
                mutableState.value = mutableState.value.copy(showNotificationPrompt = false)
        }
    }
}
