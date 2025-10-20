package sk.martinvanco.monad.auth.presentation.register

import cafe.adriel.voyager.core.model.StateScreenModel
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.core.util.Logger

class RegisterScreenModel(
    private val navigationManager: NavigationManager
) : StateScreenModel<RegisterState>(RegisterState()) {

    fun onEvent(event: RegisterEvent) {
        Logger.d("Register event received: $event", tag = "RegisterScreen")

        when(event) {
            RegisterEvent.CreateAccountButtonClick -> {
                Logger.i("Create account button clicked", tag = "RegisterScreen")
                // TODO: Implement account creation logic
                // Example: After successful registration, navigate to home
                // navigationManager.replace(HomeScreen())
            }
            is RegisterEvent.LoginButtonClick -> {
                Logger.i("Navigate back to login", tag = "RegisterScreen")
                navigationManager.navigateBack()
            }
            is RegisterEvent.OnNameFieldChange -> {
                Logger.d("Name field changed to: ${event.value}", tag = "RegisterScreen")
                mutableState.value = state.value.copy(name = event.value)
            }
            is RegisterEvent.OnEmailFieldChange -> {
                Logger.d("Email field changed to: ${event.value}", tag = "RegisterScreen")
                mutableState.value = state.value.copy(email = event.value)
            }
            is RegisterEvent.OnPasswordFieldChange -> {
                Logger.d("Password field changed (length: ${event.value.length})", tag = "RegisterScreen")
                mutableState.value = state.value.copy(password = event.value)
            }
            is RegisterEvent.OnRepeatPasswordFieldChange -> {
                Logger.d("Repeat password field changed (length: ${event.value.length})", tag = "RegisterScreen")
                mutableState.value = state.value.copy(repeatPassword = event.value)
            }
        }
    }
}
