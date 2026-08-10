package sk.martinvanco.monad.core.domain.permissions

import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.bluetooth.BLUETOOTH_LE
import dev.icerock.moko.permissions.camera.CAMERA
import dev.icerock.moko.permissions.location.BACKGROUND_LOCATION
import dev.icerock.moko.permissions.location.LOCATION

/**
 * The permissions a lab session actually needs, each with the reason a participant can act on.
 *
 * Participants are students, not engineers. "Location access is required for Bluetooth scanning to
 * work properly" is true and useless: it explains a platform rule, not a consequence. Everything
 * here is written the other way round — **what stops working, in the experiment, if you say no** —
 * because that is the only form of the question a person can answer well.
 *
 * The iOS requirements are unforgiving and worth stating plainly rather than discovering in a lab:
 * a session runs with the app in a pocket, and *When In Use* location is revoked the moment the app
 * is backgrounded. On iOS the CoreLocation beacon session is simultaneously what witnesses the
 * anchors and what buys the process the background runtime the emitter needs, so Always is not a
 * nice-to-have — without it the session records nothing at all.
 */
enum class LabPermission(
    val permission: Permission,
    val title: String,
    /** Why the experiment needs it, in one sentence a participant can act on. */
    val why: String,
    /** What is lost if it is refused. Concrete, never "some features may not work". */
    val ifMissing: String,
    /** False for permissions a participant can decline and still take part. */
    val required: Boolean,
) {
    BLUETOOTH(
        permission = Permission.BLUETOOTH_LE,
        title = "Bluetooth",
        why = "Your phone listens for the small beacons taped around the room. That is how the " +
            "experiment knows which part of the hall your phone was in.",
        ifMissing = "Your phone contributes no zone information at all.",
        required = true,
    ),

    LOCATION(
        permission = Permission.LOCATION,
        title = "Location",
        why = "Both Apple and Google put Bluetooth beacon detection behind the location " +
            "permission. Nothing here uses GPS and no map position is ever recorded — only which " +
            "beacon your phone can hear.",
        ifMissing = "Bluetooth beacons cannot be detected, so the same zone information is lost.",
        required = true,
    ),

    BACKGROUND_LOCATION(
        permission = Permission.BACKGROUND_LOCATION,
        title = "Location — Always",
        why = "The session runs with your phone in your pocket and the screen off. \"While Using " +
            "the App\" is switched off by the phone the moment you lock it, which is exactly when " +
            "the measurement is happening.",
        ifMissing = "The session stops recording as soon as you put the phone away — which is " +
            "most of the session.",
        required = true,
    ),

    CAMERA(
        permission = Permission.CAMERA,
        title = "Camera",
        why = "To scan the check-in code on the doorframe. That scan is the only thing in the " +
            "whole experiment that counts *people* rather than phones.",
        ifMissing = "You cannot check in or out, so you are not counted as a person in the room.",
        required = true,
    ),
    ;

    companion object {
        fun of(permission: Permission): LabPermission? = entries.firstOrNull { it.permission == permission }
    }
}

/** A permission and where it currently stands, for a checklist the participant can repair. */
data class PermissionStatus(
    val permission: LabPermission,
    val granted: Boolean,
    /** Refused in a way that only Settings can undo — the card must offer Settings, not a re-ask. */
    val deniedPermanently: Boolean,
) {
    val needsAttention: Boolean get() = !granted && permission.required

    /** What to do next, in words. */
    val action: String
        get() = when {
            granted -> "Granted"
            deniedPermanently -> "Open Settings to allow it"
            else -> "Tap to allow"
        }
}
