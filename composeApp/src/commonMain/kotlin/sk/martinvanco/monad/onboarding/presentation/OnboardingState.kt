package sk.martinvanco.monad.onboarding.presentation

import sk.martinvanco.monad.core.domain.permissions.Permission
import sk.martinvanco.monad.core.domain.permissions.PermissionStatus

data class OnboardingState(
    val currentPage: Int = 0,
    val permissionStatuses: Map<Permission, PermissionStatus> = emptyMap(),
    val isLoading: Boolean = false
) {
    val totalPages: Int = OnboardingStep.entries.size

    val isLastPage: Boolean = currentPage == totalPages - 1

    val currentStep: OnboardingStep = OnboardingStep.entries.getOrElse(currentPage) { OnboardingStep.WELCOME }

    fun isPermissionGranted(permission: Permission): Boolean {
        return permissionStatuses[permission] == PermissionStatus.GRANTED
    }

    fun isCurrentPermissionGranted(): Boolean {
        val permission = currentStep.permission ?: return true
        return isPermissionGranted(permission)
    }
}

enum class OnboardingStep(
    val title: String,
    val description: String,
    val permission: Permission?,
    val buttonText: String
) {
    WELCOME(
        title = "Welcome to Monad",
        description = "Let's set up your app to provide the best experience. We'll need a few permissions to enable all features.",
        permission = null,
        buttonText = "Get Started"
    ),
    BLUETOOTH(
        title = "Bluetooth Access",
        description = "We use Bluetooth to scan for nearby BLE beacons and devices during quests. This enables location-based experiences and interactions.",
        permission = Permission.BLUETOOTH_SCAN,
        buttonText = "Allow Bluetooth"
    ),
    LOCATION(
        title = "Location Access",
        description = "Location access is required for Bluetooth scanning to work properly and to provide location-based quest features.",
        permission = Permission.LOCATION,
        buttonText = "Allow Location"
    ),
    CAMERA(
        title = "Camera Access",
        description = "Camera access allows you to scan QR codes during quests and participate in augmented reality experiences.",
        permission = Permission.CAMERA,
        buttonText = "Allow Camera"
    ),
    COMPLETE(
        title = "You're All Set!",
        description = "Great! All permissions are configured. You can now enjoy the full Monad experience.",
        permission = null,
        buttonText = "Start Exploring"
    )
}
