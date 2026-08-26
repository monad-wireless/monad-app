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
 * The onboarding sequence — five pages, two permissions.
 *
 * Every permission step's copy comes from [LabPermission], which states the **consequence** of
 * refusing rather than the platform rule behind the ask. "Location access is required for
 * Bluetooth scanning to work properly" tells a student nothing they can act on; "your phone
 * cannot be heard at all, so nothing you do can be recorded" is a decision they can make.
 *
 * **Both location pages were removed on 2026-08-26**, and with them the last claim in this file
 * that was no longer true. The copy here had described a phone in a pocket with the screen off,
 * sending test packets over a lab Wi-Fi, listening for beacons. None of that is this deployment:
 * there is no access point to send packets over, no anchor has been flashed to iBeacon, and on
 * iOS the identity broadcast stops the moment the app leaves the foreground. A consent screen
 * that describes a different experiment is not consent.
 */
enum class OnboardingStep(
    val title: String,
    val description: String,
    val permission: Permission?,
    val buttonText: String,
) {
    WELCOME(
        title = "Your phone is the measurement",
        description = "The library is full of Wi-Fi, and a body standing in the way of a radio " +
            "signal changes it. Ten small boxes around the room measure that, hundreds of times " +
            "a second, and we are trying to find out whether it is enough to count people " +
            "without a camera.\n\nYour part is to be somewhere we have measured, and to let " +
            "your phone say 'someone is here' while you are. The next two screens explain the " +
            "two permissions that needs, and what is lost without each.",
        permission = null,
        buttonText = "Get started",
    ),
    BLUETOOTH(
        title = LabPermission.BLUETOOTH.title,
        description = LabPermission.BLUETOOTH.describe(),
        permission = LabPermission.BLUETOOTH.permission,
        buttonText = "Allow Bluetooth",
    ),
    // "Location — Always" was a page here until 2026-08-26 (IP-140). It asked for a
    // permission neither platform will grant from an onboarding screen — Android 11+
    // ignores the bundled request moko sends, iOS defers the prompt until the app has
    // used location in the background — so the step hung for its 30 s timeout and then
    // reported a denial nobody had made. It served the iBeacon witness role, which is
    // inert while the lab bundle ships `beacons.zones: []`.
    CAMERA(
        title = LabPermission.CAMERA.title,
        description = LabPermission.CAMERA.describe(),
        permission = LabPermission.CAMERA.permission,
        buttonText = "Allow Camera",
    ),
    TERMS(
        title = "What leaves this phone",
        description = "Everything is stored under a random participant code, never your name or " +
            "e-mail.\n\nWhat leaves this phone: which marked points you scanned and when, how " +
            "long you stood at each, and any headcounts you recorded. That is the whole " +
            "list.\n\nWhat does not: your location. This app has no code that can read a " +
            "position — no GPS, no coordinates, nothing. It also never records audio or video, " +
            "and it cannot record Wi-Fi; no phone can.\n\nContinuing also enables anonymous " +
            "crash reports.",
        permission = null,
        buttonText = "I agree",
    ),
    COMPLETE(
        title = "You're all set",
        description = "Pick a quest whenever you are in the library. The shortest takes thirty " +
            "seconds.\n\nOne thing worth knowing: keep the app open and the screen awake while " +
            "a run is going. On an iPhone the broadcast stops the moment you switch away, and " +
            "nothing on screen will tell you.",
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
