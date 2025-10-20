package sk.martinvanco.monad.quests.presentation

import cafe.adriel.voyager.core.model.StateScreenModel

class QuestsScreenModel : StateScreenModel<QuestsState>(QuestsState()) {

    fun onEvent(event: QuestsEvent) {
        // TODO: Implement event handling
    }
}
