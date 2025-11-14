package sk.martinvanco.monad.my_account.presentation

sealed interface MyAccountEvent {
    data object LogoutClick : MyAccountEvent
}
