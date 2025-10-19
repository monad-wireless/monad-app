package sk.martinvanco.blarp.quest_detail.presentation

import cafe.adriel.voyager.core.model.StateScreenModel

class QuestDetailScreenModel(
    questId: String
) : StateScreenModel<QuestDetailState>(QuestDetailState(questId = questId)) {

    fun onEvent(event: QuestDetailEvent) {
        // TODO: Handle events
    }
}
