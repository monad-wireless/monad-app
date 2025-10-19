package sk.martinvanco.blarp.quest_in_progress.presentation

import cafe.adriel.voyager.core.model.StateScreenModel

class QuestInProgressScreenModel(
    questId: String
) : StateScreenModel<QuestInProgressState>(QuestInProgressState(questId = questId)) {

    fun onEvent(event: QuestInProgressEvent) {
        // TODO: Handle events
    }
}
