package sk.martinvanco.monad.auth.presentation.register

import cafe.adriel.voyager.core.model.StateScreenModel

class RegisterScreenModel : StateScreenModel<RegisterState>(RegisterState()) {

    fun onEvent(event: RegisterEvent) {
        // TODO: Implement event handling
    }
}
