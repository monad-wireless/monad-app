package sk.martinvanco.monad.onboarding.presentation

import dev.icerock.moko.permissions.Permission
import sk.martinvanco.monad.core.domain.permissions.LabPermission

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

/**
 * The onboarding sequence.
 *
 * Every permission step's copy comes from [LabPermission], which states the **consequence** of
 * refusing rather than the platform rule behind the ask. The two versions of the location step are
 * the clearest case: "location access is required for Bluetooth scanning to work properly" tells a
 * student nothing they can act on, while "the session stops recording as soon as you put the phone
 * away" is a decision they can actually make.
 *
 * The Always-location step is new and is not optional theatre. On iOS *When In Use* is revoked the
 * instant the app is backgrounded, and a field session is a phone in a pocket with the screen off;
 * without Always the app records the one condition that never occurs during the experiment.
 */
enum class OnboardingStep(
    val title: String,
    val description: String,
    val permission: Permission?,
    val buttonText: String,
) {
    WELCOME(
        title = "You are carrying an instrument",
        description = "For the next few days this phone is part of a measurement. It listens for " +
            "small beacons in the room, and — for some sessions — sends a steady stream of test " +
            "packets over the lab Wi-Fi. It never records audio, video, or where you are outside " +
            "the room. The next few screens explain each permission and what is lost without it.",
        permission = null,
        buttonText = "Get started",
    ),
    BLUETOOTH(
        title = LabPermission.BLUETOOTH.title,
        description = LabPermission.BLUETOOTH.describe(),
        permission = LabPermission.BLUETOOTH.permission,
        buttonText = "Allow Bluetooth",
    ),
    LOCATION(
        title = LabPermission.LOCATION.title,
        description = LabPermission.LOCATION.describe(),
        permission = LabPermission.LOCATION.permission,
        buttonText = "Allow Location",
    ),
    BACKGROUND_LOCATION(
        title = LabPermission.BACKGROUND_LOCATION.title,
        description = LabPermission.BACKGROUND_LOCATION.describe(),
        permission = LabPermission.BACKGROUND_LOCATION.permission,
        buttonText = "Allow Always",
    ),
    CAMERA(
        title = LabPermission.CAMERA.title,
        description = LabPermission.CAMERA.describe(),
        permission = LabPermission.CAMERA.permission,
        buttonText = "Allow Camera",
    ),
    TERMS(
        title = "What is recorded",
        description = "Your data is stored under a random participant code, never your name or " +
            "e-mail. What leaves this phone: which beacons it heard and how strongly, which zone " +
            "you scanned into and out of, and — during illuminator sessions — the timing of the " +
            "test packets it sent. Continuing also enables anonymous crash reports.",
        permission = null,
        buttonText = "I agree",
    ),
    COMPLETE(
        title = "You're all set",
        description = "Open the app at any time to check that it is still recording and which " +
            "zone you are in. It works with the screen off — you do not need to keep it open.",
        permission = null,
        buttonText = "Start",
    ),
    ;

    companion object {
        /** The steps that actually gate a permission, for the resume-time status refresh. */
        val permissionSteps: List<OnboardingStep> get() = entries.filter { it.permission != null }
    }
}

private fun LabPermission.describe(): String = "$why\n\nWithout it: $ifMissing"
