package sk.martinvanco.blarp.auth.presentation.login

import cafe.adriel.voyager.core.model.StateScreenModel

class LoginScreenModel : StateScreenModel<LoginState>(LoginState()) {

    fun onEvent(event: LoginEvent) {
        // TODO: Implement event handling
    }
}
