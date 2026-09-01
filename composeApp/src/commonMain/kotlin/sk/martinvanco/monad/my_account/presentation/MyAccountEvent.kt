package sk.martinvanco.monad.my_account.presentation

sealed interface MyAccountEvent {
    data object LogoutClick : MyAccountEvent
    data object DeleteAccountClick : MyAccountEvent
    data object ConfirmDeleteAccount : MyAccountEvent
    data object DismissDeleteDialog : MyAccountEvent
}
