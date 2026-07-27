package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLLocationAccuracyThreeKilometers
import platform.Foundation.NSBundle
import platform.UIKit.UIApplication

/**
 * iOS residency — a live CoreLocation session.
 *
 * iOS grants no general "keep running in the background" permission. What it does grant is
 * continuous runtime to an app holding an active location session with Always authorization and
 * the `location` background mode declared. So residency here is not a separate mechanism bolted
 * next to the witness: **it is the same location session**, which is why the instrument acquires
 * residency before it does anything else and why a phone that cannot witness also cannot
 * illuminate.
 *
 * A significant-location-change subscription is used rather than continuous high-accuracy updates:
 * it is the cheapest session that still keeps the process alive, and the instrument does not want
 * the phone's coordinates — it wants the runtime.
 */
@OptIn(ExperimentalForeignApi::class)
actual class BackgroundResidency actual constructor() {

    private val manager = CLLocationManager()

    private val _isActive = MutableStateFlow(false)
    actual val isActive: Flow<Boolean> = _isActive.asStateFlow()

    actual suspend fun acquire(reason: String): Result<Unit> = runCatching {
        val status = CLLocationManager.authorizationStatus()
        if (status != kCLAuthorizationStatusAuthorizedAlways) {
            manager.requestAlwaysAuthorization()
            throw IllegalStateException(
                "Always location authorization is required — 'When In Use' is revoked the moment " +
                    "the app leaves the foreground, which is the only state a session runs in"
            )
        }
        if (!hasBackgroundMode(LOCATION_MODE)) {
            throw IllegalStateException(
                "Info.plist UIBackgroundModes is missing '$LOCATION_MODE'; the process will be " +
                    "suspended on backgrounding"
            )
        }

        manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false
        manager.startMonitoringSignificantLocationChanges()

        _isActive.value = true
        Napier.i("[lab] ios residency acquired: $reason")
    }

    actual fun release() {
        manager.stopMonitoringSignificantLocationChanges()
        manager.allowsBackgroundLocationUpdates = false
        _isActive.value = false
    }

    actual fun diagnostics(): List<ResidencyCheck> {
        val status = CLLocationManager.authorizationStatus()
        val always = status == kCLAuthorizationStatusAuthorizedAlways
        return listOf(
            ResidencyCheck(
                name = "location_always",
                satisfied = always,
                detail = if (always) "granted" else "NOT granted — background residency impossible",
            ),
            ResidencyCheck(
                name = "background_mode_location",
                satisfied = hasBackgroundMode(LOCATION_MODE),
                detail = "Info.plist UIBackgroundModes",
            ),
            ResidencyCheck(
                name = "background_mode_bluetooth",
                satisfied = hasBackgroundMode(BLUETOOTH_MODE),
                detail = "optional; CoreLocation carries the anchors, so this is belt-and-braces",
            ),
            ResidencyCheck(
                name = "location_services_enabled",
                satisfied = CLLocationManager.locationServicesEnabled(),
                detail = "device-wide toggle",
            ),
            ResidencyCheck(
                name = "session_active",
                satisfied = _isActive.value,
                detail = if (_isActive.value) "location session live" else "no session",
            ),
        )
    }

    actual suspend fun requestPrerequisites(): Result<Unit> = runCatching {
        if (CLLocationManager.authorizationStatus() != kCLAuthorizationStatusAuthorizedAlways) {
            manager.requestAlwaysAuthorization()
        }
        Unit
    }

    private fun hasBackgroundMode(mode: String): Boolean {
        val modes = NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes") as? List<*>
        return modes?.any { it as? String == mode } == true
    }

    /** Deep link to this app's settings pane, offered by the console when a check fails. */
    fun openSettings() {
        val url = platform.Foundation.NSURL.URLWithString(
            platform.UIKit.UIApplicationOpenSettingsURLString
        ) ?: return
        UIApplication.sharedApplication.openURL(url)
    }

    private companion object {
        const val LOCATION_MODE = "location"
        const val BLUETOOTH_MODE = "bluetooth-central"
    }
}
