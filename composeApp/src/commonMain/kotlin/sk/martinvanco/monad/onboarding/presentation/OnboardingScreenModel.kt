package sk.martinvanco.monad.onboarding.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.presentation.login.LoginScreen
import sk.martinvanco.monad.core.data.repository.SettingsRepository
import sk.martinvanco.monad.core.domain.permissions.Permission
import sk.martinvanco.monad.core.domain.permissions.PermissionHandler
import sk.martinvanco.monad.core.domain.permissions.PermissionStatus
import sk.martinvanco.monad.core.navigation.NavigationManager

class OnboardingScreenModel(
    private val navigationManager: NavigationManager,
    private val permissionHandler: PermissionHandler,
    private val settingsRepository: SettingsRepository
) : StateScreenModel<OnboardingState>(OnboardingState()) {

    init {
        checkAllPermissions()
    }

    private fun checkAllPermissions() {
        screenModelScope.launch {
            val statuses = mutableMapOf<Permission, PermissionStatus>()
            Permission.entries.forEach { permission ->
                statuses[permission] = permissionHandler.checkPermission(permission)
            }
            mutableState.value = state.value.copy(permissionStatuses = statuses)
        }
    }

    fun onNextClick() {
        val currentState = state.value

        when (currentState.currentStep) {
            OnboardingStep.WELCOME -> {
                goToNextPage()
            }
            OnboardingStep.BLUETOOTH,
            OnboardingStep.LOCATION,
            OnboardingStep.CAMERA -> {
                val permission = currentState.currentStep.permission
                if (permission != null) {
                    requestPermission(permission)
                } else {
                    goToNextPage()
                }
            }
            OnboardingStep.COMPLETE -> {
                completeOnboarding()
            }
        }
    }

    private fun requestPermission(permission: Permission) {
        screenModelScope.launch {
            mutableState.value = state.value.copy(isLoading = true)

            val currentStatus = permissionHandler.checkPermission(permission)
            if (currentStatus == PermissionStatus.GRANTED) {
                mutableState.value = state.value.copy(
                    isLoading = false,
                    permissionStatuses = state.value.permissionStatuses + (permission to PermissionStatus.GRANTED)
                )
                goToNextPage()
                return@launch
            }

            if (currentStatus == PermissionStatus.DENIED_PERMANENTLY) {
                mutableState.value = state.value.copy(isLoading = false)
                permissionHandler.openAppSettings()
                return@launch
            }

            val status = permissionHandler.requestPermission(permission)
            mutableState.value = state.value.copy(
                isLoading = false,
                permissionStatuses = state.value.permissionStatuses + (permission to status)
            )

            if (status == PermissionStatus.GRANTED) {
                goToNextPage()
            }
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

    fun openAppSettings() {
        permissionHandler.openAppSettings()
    }

    fun refreshPermissions() {
        checkAllPermissions()
    }

    private fun completeOnboarding() {
        screenModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
            navigationManager.replace(LoginScreen())
        }
    }
}
