package sk.martinvanco.monad.auth.presentation.splash

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.auth.domain.AuthManager
import sk.martinvanco.monad.auth.domain.OperatorAccess
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.core.data.repository.SettingsRepository
import sk.martinvanco.monad.core.navigation.NavigationManager
import sk.martinvanco.monad.main.presentation.MainContainerScreen
import sk.martinvanco.monad.onboarding.presentation.OnboardingScreen
import sk.martinvanco.monad.quests.presentation.active_quest.ActiveQuestScreen

class SplashScreenModel(
    private val navigationManager: NavigationManager,
    private val authManager: AuthManager,
    private val settingsRepository: SettingsRepository,
    private val userRepository: UserRepository,
    private val operatorAccess: OperatorAccess,
) : StateScreenModel<SplashState>(SplashState()) {

    /**
     * Decide the first screen, and ALWAYS reach one.
     *
     * The whole body is guarded, and that is the point rather than defensive habit. This runs in a
     * bare `screenModelScope.launch` with no failure path: any throw anywhere in the sequence
     * cancelled the coroutine, and because the splash's only content is a logo and a spinner, the
     * app then sat on that spinner for ever, with nothing in the log saying why. "The app does not
     * start" is the exact symptom, and it is indistinguishable from a hang.
     *
     * Several steps here can throw for ordinary reasons. `isOnboardingCompleted`,
     * `getCurrentUser` and `deleteAllUsers` are SQLite reads and writes. `clearUser` additionally
     * calls the sign-out observer, which talks to the server — and `clearUser` is on the path an
     * EXPIRED TOKEN takes, which is the commonest way a returning participant launches the app.
     *
     * So every branch logs which way it went, and any failure lands on the login screen. Login is
     * the right fallback: it is reachable without a token, it is where a broken session has to end
     * up anyway, and the worst case is one unnecessary sign-in rather than an app nobody can open.
     */
    fun checkAuthStatus() {
        screenModelScope.launch {
            val destination = runCatching { decide() }.getOrElse { failure ->
                Napier.e("[splash] could not decide a start screen, going to login", failure)
                LoginScreen()
            }
            Napier.i("[splash] -> ${destination::class.simpleName}")
            navigationManager.replace(destination)
        }
    }

    private suspend fun decide(): Screen {
        // The cached operator flag first, so the home screen's first frame already draws the
        // right half of the app. `validateToken` below refreshes it from the server; this only
        // covers the seconds before that answers, and a launch with no route out.
        operatorAccess.restore()

        // First check if onboarding has been completed
        val onboardingCompleted = settingsRepository.isOnboardingCompleted()
        if (!onboardingCompleted) {
            return OnboardingScreen()
        }

        val user = authManager.getCurrentUser()
        if (user == null) {
            return LoginScreen()
        }

        val token = user.token
        if (token == null) {
            clearSessionQuietly("no token on the stored account")
            return LoginScreen()
        }

        val isValid = authManager.validateToken(token)
        if (!isValid) {
            clearSessionQuietly("the stored token was refused")
            return LoginScreen()
        }

            // IP-140 — resume a run this handset is still enrolled in.
            //
            // Before this there was no resume path at all: `ActiveQuestScreen` was constructed in
            // exactly one place, the detail screen's Start button, and nothing read the active-quest
            // pointer the app has always written. A participant whose app died mid-quest therefore
            // came back to the quest list, pressed Start, and got a 409 — with the enrolment still
            // open, the local step rows still on disk, and no way back to either.
            //
            // Harmless when it was not needed: `clearActiveQuest` runs in the same block that clears
            // the local step rows, and only after the completion reached the server, so a pointer
            // that survives is by construction a run that did not finish. Failures fall through to
            // the normal home screen rather than trapping someone on a splash.
        val resumable = runCatching { userRepository.getCurrentUserActiveQuestId() }.getOrNull()
        if (resumable != null) {
            return ActiveQuestScreen(questId = resumable)
        }

        return MainContainerScreen()
    }

    /**
     * Drop the session without letting the drop decide the screen.
     *
     * `clearUser` deletes the account row AND runs the sign-out observer, which unregisters the
     * push token against a server this device may not be able to reach — with a token that has
     * just been refused. None of that is a reason to strand somebody on a splash screen: the
     * account is already unusable, and the next sign-in overwrites whatever is left behind.
     *
     * **Bounded, and with `await` rather than a plain `withTimeoutOrNull` around the call.** The
     * defect this replaces was a sign-out that could not be cancelled at all: a Firebase token read
     * suspended in a non-cancellable `suspendCoroutine` and never resumed, so every timeout wrapped
     * directly around it expired without releasing anything, and the launch spinner ran for ever.
     * `Deferred.await()` is cancellable whatever the work behind it is doing, so this releases the
     * splash on time even when the sign-out itself cannot be stopped.
     *
     * [PushCredentialsAdapter] fixes that particular hang at its source. This bound stays anyway:
     * "the app always reaches a screen" should not rest on every future sign-out step being
     * well-behaved.
     */
    private suspend fun clearSessionQuietly(reason: String) = coroutineScope {
        Napier.i("[splash] signing out: $reason")
        val signOut = async {
            runCatching { authManager.clearUser() }
                .onFailure { Napier.w("[splash] sign-out did not complete cleanly: ${it.message}") }
        }
        if (withTimeoutOrNull(SIGN_OUT_CAP_MS) { signOut.await() } == null) {
            Napier.w("[splash] sign-out did not finish in ${SIGN_OUT_CAP_MS} ms — going to login anyway")
            // Cancelled, because `coroutineScope` waits for its children: without this the cap
            // would delay the decision instead of bounding it.
            signOut.cancel()
        }
        Unit
    }

    private companion object {
        /**
         * Five seconds, which is longer than every bound inside the sign-out path put together
         * (a 3 s push-token unregister, itself now holding a 2 s token read). Reaching this means
         * something new is blocking, and the log line above is how the next reader finds out.
         */
        const val SIGN_OUT_CAP_MS = 5_000L
    }
}
