package sk.martinvanco.monad.my_account.presentation

data class MyAccountState(
    val userName: String? = null,
    val userEmail: String = "",
    val isLoading: Boolean = true,
    val showDeleteDialog: Boolean = false,
    val isDeleting: Boolean = false,
    val deleteError: String? = null,
    val showChangePasswordDialog: Boolean = false,
    val isChangingPassword: Boolean = false,
    val changePasswordError: String? = null,
    /** Set after a successful change, so the screen can say so once the dialog has closed. */
    val passwordChanged: Boolean = false
)
