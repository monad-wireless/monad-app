package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS residency — **not available on this build, and honestly so** (2026-08-26).
 *
 * iOS grants no general "keep running in the background" permission. The only thing it does grant
 * is continuous runtime to an app holding an active CoreLocation session with Always authorization
 * and the `location` background mode. This build has none of those: every location permission,
 * usage description and background mode was removed, and the app now contains no code that can
 * read a position.
 *
 * So this reports unavailable rather than pretending. Three reasons that is the right answer here
 * and not a regression:
 *
 * 1. **Nothing on this deployment used it.** The two live roles are broadcaster (CoreBluetooth —
 *    no location) and track (ARKit — camera only). Both hold the screen through [ForegroundWake],
 *    which is the correct mechanism for a session the participant is looking at.
 * 2. **It was a hard gate on every session in the app.** `LabInstrument.start()` used to acquire
 *    residency unconditionally, and the old implementation threw unless the status was
 *    `AuthorizedAlways` — so a quest that only advertised a BLE frame could not start at all.
 * 3. **The permission was ungrantable anyway.** iOS defers the "Keep Allow Always?" prompt until
 *    the app has already used location in the background, so it could never be granted from an
 *    onboarding screen; the step hung for its timeout and reported a denial nobody had made.
 *
 * The role that wanted it — witnessing iBeacon anchors with the phone in a pocket — is inert while
 * the lab bundle ships `beacons.zones: []` and the anchors are unflashed. **Reinstating it means
 * bringing CoreLocation back**: the Always permission, the `location` background mode, the usage
 * descriptions, and a line in the participant consent copy that currently says no location of any
 * kind is recorded. That is a deliberate decision to take again, not an accident to drift into.
 *
 * Android is unaffected — its residency is a foreground service plus a battery-optimisation
 * exemption, and touches no location API.
 */
actual class BackgroundResidency actual constructor() {

    private val _isActive = MutableStateFlow(false)
    actual val isActive: Flow<Boolean> = _isActive.asStateFlow()

    actual suspend fun acquire(reason: String): Result<Unit> {
        Napier.i("[lab] ios residency unavailable (no location capability in this build): $reason")
        return Result.failure(IllegalStateException(UNAVAILABLE))
    }

    actual fun release() {
        _isActive.value = false
    }

    actual fun diagnostics(): List<ResidencyCheck> = listOf(
        ResidencyCheck(
            name = "Background residency",
            satisfied = false,
            detail = UNAVAILABLE,
        ),
    )

    actual suspend fun requestPrerequisites(): Result<Unit> =
        Result.failure(IllegalStateException(UNAVAILABLE))

    private companion object {
        const val UNAVAILABLE =
            "iOS background residency needs an Always-location session, and this build has no " +
                "location capability at all. Sessions run in the foreground with the screen held " +
                "awake instead."
    }
}
