package sk.martinvanco.monad.onboarding.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics
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
            OnboardingStep.LOCATION,
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

    private fun enableCrashReporting() {
        Firebase.crashlytics.setCrashlyticsCollectionEnabled(true)
    }

    private fun completeOnboarding() {
        screenModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
            navigationManager.replace(LoginScreen())
        }
    }
}
