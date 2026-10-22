package sk.martinvanco.monad.my_account.presentation

sealed interface MyAccountEvent {
    data object LogoutClick : MyAccountEvent
    data object DeleteAccountClick : MyAccountEvent
    data object ConfirmDeleteAccount : MyAccountEvent
    data object DismissDeleteDialog : MyAccountEvent
    data object ChangePasswordClick : MyAccountEvent
    data object DismissChangePassword : MyAccountEvent
    data class ConfirmChangePassword(
        val currentPassword: String,
        val newPassword: String,
        val repeatPassword: String,
    ) : MyAccountEvent
}
