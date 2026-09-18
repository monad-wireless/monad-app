package sk.martinvanco.monad.core.autofill

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Asks the platform to close the autofill session, so a password manager may offer to save what
 * the participant just typed.
 *
 * WHY THIS IS NOT A `LaunchedEffect` ON THE LOGIN SCREEN. The commit must happen only after the
 * server accepted the credentials — committing on the button press would offer to save a password
 * that was just refused, which is the one thing worse than not offering at all. But the screen
 * model's success path does two things in a row:
 *
 *     mutableState.value = state.value.copy(...)      // recomposes the login screen
 *     navigationManager.replaceAll(MainContainerScreen())   // destroys it
 *
 * and those two race. The state write schedules a recomposition on the Compose frame clock, while
 * the navigation command travels through a `MutableSharedFlow` whose collector resumes on the next
 * main-thread dispatch. The navigation normally wins, so an effect hung off the login screen's own
 * state would be disposed before it ever ran, and the save prompt would simply not appear — with
 * nothing in any log to say so.
 *
 * So the request is parked here and drained in `App()`, which is the one composable that outlives
 * every screen. The same shape as [sk.martinvanco.monad.notifications.domain.PendingPushRoute] and
 * for the same reason: a signal whose producer and consumer have different lifetimes.
 *
 * On iOS this is a no-op by construction. `LocalAutofillManager` is null there (Compose
 * Multiplatform's `RootNodeOwner.skiko.kt` returns null), and iOS needs no commit anyway — the
 * system offers to save when a screen carrying a `UITextContentTypePassword` field goes away.
 */
object CredentialSave {

    private val _requested = MutableStateFlow(false)

    /** True while a commit is waiting to be drained. Collected once, in `App()`. */
    val requested: StateFlow<Boolean> = _requested.asStateFlow()

    /**
     * Call on the success path of a sign-in or a registration, before navigating away.
     *
     * Never on a failure: a rejected password must not reach the manager's save prompt.
     */
    fun request() {
        _requested.value = true
    }

    /**
     * Take the request, if any, clearing it.
     *
     * Take-once and atomic, so a recomposition cannot commit the same session twice. A second
     * commit is not harmless — the framework treats the session as already closed and the prompt
     * for the next sign-in can be swallowed.
     */
    fun consume(): Boolean = _requested.getAndUpdate { false }

    /** Test seam — no production caller should need this. */
    fun clear() {
        _requested.value = false
    }
}
