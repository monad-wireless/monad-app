package dev.dubec.monad.onboarding.presentation

import dev.icerock.moko.permissions.Permission
import dev.dubec.monad.core.domain.permissions.LabPermission

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
    /**
     * What skipping this page costs, in the participant's own terms.
     *
     * Printed ON the skip control. "Skip for now" is a promise the app does not keep: the
     * permission is not asked for again, nothing later says it is missing, and the participant
     * finds out when a quest quietly records nothing. A person may still decline — that is their
     * right — but they should decline the consequence rather than the dialog.
     */
    val skipCost: String? = null,
) {
    WELCOME(
        title = "Welcome to the study",
        description = "A person in the path of a Wi-Fi signal changes it a little. Small sensor " +
            "boxes in the library record these changes. We study whether they are enough to " +
            "estimate how many people are in a room, without a camera.\n\n" +
            "Your part is to complete short quests. They give us the known values that we " +
            "compare the estimate with.",
        permission = null,
        buttonText = "Continue",
    ),
    BLUETOOTH(
        title = LabPermission.BLUETOOTH.title,
        description = LabPermission.BLUETOOTH.describe(),
        permission = LabPermission.BLUETOOTH.permission,
        buttonText = "Allow Bluetooth",
        skipCost = "Skip — my phone will not be heard",
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
        skipCost = "Skip — I will not scan any codes",
    ),
    TERMS(
        title = "What leaves this phone",
        description = "During a quest the app sends what you scan, count and answer, with the times, " +
            "a description of your phone, and technical readings about the recording. A tracked " +
            "walk also sends the movement of the phone and, with LiDAR, an outline of the room " +
            "(no images).\n\n" +
            "The sensor boxes record the Bluetooth signal of your phone. The study uses it to " +
            "estimate where in the library the phone was.\n\n" +
            "Measurements are stored under a participant code that the server links to your " +
            "account.\n\n" +
            "NEVER\nNo GPS position, no photos, video or audio, no Wi-Fi data, no advertising " +
            "identifier.\n\n" +
            "Crash reports go to Google Firebase and cannot be turned off in this version. The " +
            "privacy policy lists everything.",
        permission = null,
        buttonText = "I agree",
    ),
    COMPLETE(
        title = "You are ready",
        description = "When you are in the library, choose a quest and follow its steps. You can " +
            "read what a quest involves before you start it.\n\n" +
            "ONE THING TO REMEMBER\nPlease keep the app open and the screen on until the quest " +
            "ends. On an iPhone the Bluetooth signal stops when you leave the app, and the app " +
            "cannot warn you.",
        permission = null,
        buttonText = "See the quests",
    ),
    ;

    companion object {
        /** The steps that actually gate a permission, for the resume-time status refresh. */
        val permissionSteps: List<OnboardingStep> get() = entries.filter { it.permission != null }
    }
}

private fun LabPermission.describe(): String = "$why\n\nWithout it: $ifMissing"
