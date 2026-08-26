package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import sk.martinvanco.monad.ble.domain.BleScanner

/**
 * iOS witness — **removed with the location capability** (2026-08-26).
 *
 * There is no iOS witness without CoreLocation, and that is a platform fact rather than a design
 * choice. iOS does not surface iBeacon frames through CoreBluetooth at all: an app can only learn
 * that a beacon is nearby by monitoring or ranging a `CLBeaconRegion`, which is a location API and
 * needs a location permission. So dropping location drops iOS witnessing with it.
 *
 * **Android is unaffected.** Its witness reads iBeacon frames off a raw BLE scan, and since the
 * manifest gained `neverForLocation` on `BLUETOOTH_SCAN` it needs no location permission to do it.
 *
 * What is actually lost, stated plainly rather than buried:
 *
 * - Nothing today. The lab bundle ships `beacons.zones: []` and the ESP32 anchors have never been
 *   reflashed to iBeacon, so no anchor resolves to a zone on any platform. The role has produced
 *   no data on this deployment, ever.
 * - The **Phase 5** design, which was the real fix for iOS background presence: anchors advertise
 *   iBeacon, and CoreLocation region monitoring wakes even a terminated app on entry *and* exit,
 *   which would also remove the human check-out. That path now needs CoreLocation brought back —
 *   the Always permission, the `location` background mode, the usage descriptions, and an edit to
 *   the consent copy that currently promises no location of any kind. A deliberate decision to
 *   take again, not one to drift into.
 *
 * Reports unavailable rather than failing silently: a witness that returns success and never emits
 * an observation is the exact failure mode this codebase keeps being bitten by.
 */
actual class BeaconWitness actual constructor(
    @Suppress("unused") private val scanner: BleScanner,
) {
    private val _observations = MutableSharedFlow<BeaconObservation>(extraBufferCapacity = 1)
    actual val observations: Flow<BeaconObservation> = _observations.asSharedFlow()

    private val _transitions = MutableSharedFlow<ZoneTransition>(extraBufferCapacity = 1)
    actual val transitions: Flow<ZoneTransition> = _transitions.asSharedFlow()

    private val _isWitnessing = MutableStateFlow(false)
    actual val isWitnessing: Flow<Boolean> = _isWitnessing.asStateFlow()

    actual suspend fun start(plan: BeaconPlan): Result<Unit> {
        Napier.w("[lab] ios witness unavailable: $UNAVAILABLE")
        return Result.failure(IllegalStateException(UNAVAILABLE))
    }

    actual fun stop() {
        _isWitnessing.value = false
    }

    actual fun residencyDiagnostics(): List<String> = listOf(
        "witness=unavailable",
        "reason=no location capability in this build",
        "note=iOS surfaces iBeacon only through CoreLocation; Android witnessing is unaffected",
    )

    private companion object {
        const val UNAVAILABLE =
            "iOS can only see iBeacon frames through CoreLocation, and this build has no location " +
                "capability. Anchor witnessing is Android-only until the anchors are reflashed and " +
                "the location capability is deliberately restored."
    }
}
