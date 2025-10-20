package sk.martinvanco.monad.my_account.presentation

import cafe.adriel.voyager.core.model.StateScreenModel

class MyAccountScreenModel : StateScreenModel<MyAccountState>(MyAccountState()) {

    fun onEvent(event: MyAccountEvent) {
        // TODO: Implement event handling
    }
}
