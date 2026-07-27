package sk.martinvanco.monad.lab.domain

import kotlinx.coroutines.flow.Flow

/**
 * Keeps the instrument alive while the app is backgrounded or the screen is off.
 *
 * This is the binding constraint of the whole design, not a nicety: a field session is a phone in
 * a pocket, so a measurement taken with the app in the foreground measures a condition that will
 * never occur. Residency is therefore proven before illumination is measured.
 *
 * - **iOS** — an active `CLLocationManager` beacon session with Always authorization plus the
 *   `location` background mode. The system keeps the process running for the location session, and
 *   the paced emitter rides along on it.
 * - **Android** — a foreground service (`connectedDevice`), a partial wake lock, and a prompt for
 *   the battery-optimisation exemption without which OEM ROMs will still freeze the process.
 */
expect class BackgroundResidency() {

    val isActive: Flow<Boolean>

    /** Acquire residency. Returns failure with a human-readable reason the console can show. */
    suspend fun acquire(reason: String): Result<Unit>

    fun release()

    /** Per-platform checks, each a `name -> ok/why-not` line for the lab console. */
    fun diagnostics(): List<ResidencyCheck>

    /**
     * Ask the platform for whatever the user must grant for residency to hold (Always location on
     * iOS; battery-optimisation exemption on Android). Safe to call repeatedly.
     */
    suspend fun requestPrerequisites(): Result<Unit>
}

data class ResidencyCheck(
    val name: String,
    val satisfied: Boolean,
    val detail: String,
)
