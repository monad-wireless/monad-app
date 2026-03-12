package sk.martinvanco.monad.onboarding.presentation

import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.bluetooth.BLUETOOTH_LE
import dev.icerock.moko.permissions.camera.CAMERA
import dev.icerock.moko.permissions.location.LOCATION

data class OnboardingState(
    val currentPage: Int = 0,
    val grantedPermissions: Set<Permission> = emptySet(),
    val deniedPermanently: Set<Permission> = emptySet(),
    val isLoading: Boolean = false
) {
    val totalPages: Int = OnboardingStep.entries.size

    val isLastPage: Boolean = currentPage == totalPages - 1

    val currentStep: OnboardingStep = OnboardingStep.entries.getOrElse(currentPage) { OnboardingStep.WELCOME }

    fun isPermissionGranted(permission: Permission): Boolean = permission in grantedPermissions

    fun isPermissionDeniedPermanently(permission: Permission): Boolean = permission in deniedPermanently

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
        permission = Permission.BLUETOOTH_LE,
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
    TERMS(
        title = "Terms of Service",
        description = "By continuing, you agree to our Terms of Service. This includes anonymous crash reporting to help us improve the app. We collect basic device info and error data when something goes wrong - no personal data is shared.",
        permission = null,
        buttonText = "I Agree"
    ),
    COMPLETE(
        title = "You're All Set!",
        description = "Great! All permissions are configured. You can now enjoy the full Monad experience.",
        permission = null,
        buttonText = "Start Exploring"
    )
}
