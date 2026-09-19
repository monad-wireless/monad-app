package sk.martinvanco.monad.auth.presentation.login

data class LoginState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    /**
     * The "Forgot Password?" dialog. There is no reset flow to navigate to — not in this app, not
     * in the API, and not in the admin, which turns its own link off for the same reason — so the
     * control explains the recovery that does exist instead of doing nothing.
     */
    val showPasswordHelp: Boolean = false
)
