package sk.martinvanco.monad.core.domain.permissions

import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.bluetooth.BLUETOOTH_LE
import dev.icerock.moko.permissions.camera.CAMERA

/**
 * The permissions a lab session actually needs, each with the reason a participant can act on.
 *
 * Participants are students, not engineers. "Location access is required for Bluetooth scanning to
 * work properly" is true and useless: it explains a platform rule, not a consequence. Everything
 * here is written the other way round — **what stops working, in the experiment, if you say no** —
 * because that is the only form of the question a person can answer well.
 *
 * **"Location — Always" was removed on 2026-08-26 (IP-140).** It had been marked required, and on
 * iOS it was a hard gate on every session in the app: `LabInstrument.start()` acquired background
 * residency unconditionally, and `BackgroundResidency.ios.kt` throws unless the status is
 * `AuthorizedAlways`. So a quest that only advertises a BLE frame — touching no location API on
 * either platform — could not start.
 *
 * It was also ungrantable. Android 11+ ignores a request that bundles background with foreground
 * location, which is exactly what moko 0.20.1 sends, so no dialog appeared and the onboarding step
 * hung for its full 30 s timeout and then reported a denial nobody had made. iOS defers the
 * "Keep Allow Always?" prompt until the app has actually used location in the background, so it
 * could not be granted from an onboarding screen either.
 *
 * The role it served — witnessing iBeacon anchors with the phone in a pocket — is inert on this
 * deployment: the lab bundle ships `beacons.zones: []`, so no anchor resolves to a zone. Residency
 * is now requested only by a session that declares `witness`, and a refusal is a warning rather
 * than a failure.
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
        why = "Your phone broadcasts a short anonymous beep that the boxes around the room can " +
            "hear. That is how a measurement gets attached to the right run.",
        ifMissing = "Your phone cannot be heard at all, so nothing you do can be recorded.",
        required = true,
    ),

    CAMERA(
        permission = Permission.CAMERA,
        title = "Camera",
        why = "To scan the codes on the walls and on the grey boxes. A scan is what tells us " +
            "which surveyed point you were standing at.",
        ifMissing = "You cannot scan a point, so most quests cannot record where you were.",
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
