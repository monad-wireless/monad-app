package sk.martinvanco.monad.home.presentation

import cafe.adriel.voyager.core.model.StateScreenModel

class HomeScreenModel : StateScreenModel<HomeState>(HomeState()) {

    fun onEvent(event: HomeEvent) {
        // TODO: Implement event handling
    }
}
