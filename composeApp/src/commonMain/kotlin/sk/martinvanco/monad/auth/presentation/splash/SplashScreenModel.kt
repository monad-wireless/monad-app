package sk.martinvanco.monad.auth.presentation.splash

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.auth.domain.AuthManager
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
) : StateScreenModel<SplashState>(SplashState()) {

    fun checkAuthStatus() {
        screenModelScope.launch {
            // First check if onboarding has been completed
            val onboardingCompleted = settingsRepository.isOnboardingCompleted()
            if (!onboardingCompleted) {
                navigationManager.replace(OnboardingScreen())
                return@launch
            }

            val user = authManager.getCurrentUser()
            if (user == null) {
                navigationManager.replace(LoginScreen())
                return@launch
            }

            val token = user.token
            if (token == null) {
                authManager.clearUser()
                navigationManager.replace(LoginScreen())
                return@launch
            }

            val isValid = authManager.validateToken(token)
            if (!isValid) {
                authManager.clearUser()
                navigationManager.replace(LoginScreen())
                return@launch
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
                navigationManager.replace(ActiveQuestScreen(questId = resumable))
                return@launch
            }

            navigationManager.replace(MainContainerScreen())
        }
    }
}
