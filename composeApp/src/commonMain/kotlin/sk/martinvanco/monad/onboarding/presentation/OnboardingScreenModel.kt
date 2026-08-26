package sk.martinvanco.monad.onboarding.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics
import io.github.aakira.napier.Napier
import dev.icerock.moko.permissions.Permission
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.core.data.repository.SettingsRepository
import sk.martinvanco.monad.core.navigation.NavigationManager

class OnboardingScreenModel(
    private val navigationManager: NavigationManager,
    private val settingsRepository: SettingsRepository
) : StateScreenModel<OnboardingState>(OnboardingState()) {

    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onNextClick() {
        val currentState = state.value

        when (currentState.currentStep) {
            OnboardingStep.WELCOME -> {
                goToNextPage()
            }
            OnboardingStep.BLUETOOTH,
            OnboardingStep.CAMERA -> {
                val permission = currentState.currentStep.permission ?: return
                if (permission in currentState.grantedPermissions) {
                    goToNextPage()
                } else if (permission in currentState.deniedPermanently) {
                    screenModelScope.launch { _events.send(OnboardingEvent.OpenAppSettings) }
                } else {
                    screenModelScope.launch { _events.send(OnboardingEvent.RequestPermission(permission)) }
                }
            }
            OnboardingStep.TERMS -> {
                enableCrashReporting()
                goToNextPage()
            }
            OnboardingStep.COMPLETE -> {
                completeOnboarding()
            }
        }
    }

    fun onPermissionResult(permission: Permission, granted: Boolean, deniedPermanently: Boolean) {
        val s = state.value
        mutableState.value = s.copy(
            grantedPermissions = if (granted) s.grantedPermissions + permission else s.grantedPermissions,
            deniedPermanently = if (deniedPermanently) s.deniedPermanently + permission else s.deniedPermanently - permission
        )
        if (granted) goToNextPage()
    }

    fun updatePermissionStatus(permission: Permission, granted: Boolean) {
        val s = state.value
        mutableState.value = if (granted) {
            s.copy(
                grantedPermissions = s.grantedPermissions + permission,
                deniedPermanently = s.deniedPermanently - permission
            )
        } else {
            s.copy(grantedPermissions = s.grantedPermissions - permission)
        }
    }

    fun onSkipClick() {
        goToNextPage()
    }

    private fun goToNextPage() {
        val currentState = state.value
        if (currentState.currentPage < currentState.totalPages - 1) {
            mutableState.value = currentState.copy(currentPage = currentState.currentPage + 1)
        }
    }

    fun goToPage(page: Int) {
        if (page in 0 until state.value.totalPages) {
            mutableState.value = state.value.copy(currentPage = page)
        }
    }

    /**
     * Crash reporting is **best-effort and optional**, matching the build: `google-services.json`
     * carries per-deployment secrets, is not in the repository, and its Gradle plugins are applied
     * only when the file is present. Without it there is no default `FirebaseApp`, and calling
     * Crashlytics throws `IllegalStateException` — which used to take down the Terms step and made
     * a clean checkout unusable past onboarding (fresh clones, CI builds, and any lab handset
     * flashed from source).
     *
     * Telemetry must never be the reason a participant cannot start a session, so a missing
     * Firebase degrades to "no crash reporting" and is logged rather than thrown.
     */
    private fun enableCrashReporting() {
        runCatching { Firebase.crashlytics.setCrashlyticsCollectionEnabled(true) }
            .onFailure { Napier.w("[onboarding] crash reporting unavailable, continuing: ${it.message}") }
    }

    private fun completeOnboarding() {
        screenModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
            navigationManager.replace(LoginScreen())
        }
    }
}
