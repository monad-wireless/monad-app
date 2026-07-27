package sk.martinvanco.monad.lab.domain

import kotlinx.coroutines.flow.Flow
import sk.martinvanco.monad.ble.domain.BleScanner

/**
 * The witness role: observe surveyed anchors and report RSSI samples and zone transitions, from a
 * **backgrounded or terminated** app.
 *
 * The two platforms reach this through entirely different frameworks, and the difference is not
 * cosmetic:
 *
 * - **iOS** — CoreLocation. `startMonitoring(for: CLBeaconRegion)` delivers enter/exit events even
 *   after the app has been terminated (the system relaunches it), and `startRangingBeacons`
 *   supplies RSSI while the app holds an active location session with Always authorization. That
 *   same session is what keeps the process alive for the illuminator role, so on iOS residency and
 *   witnessing are one mechanism, not two.
 * - **Android** — an ordinary BLE scan inside a foreground service, with [IBeaconParser] pulling
 *   the identity out of manufacturer data. Zone transitions are synthesised from the sample stream
 *   rather than delivered by the OS.
 *
 * Region monitoring is the load-bearing signal; ranging is the interpolant. A design that needs a
 * dense background RSSI stream is an Android-only design, and should be avoided.
 */
expect class BeaconWitness(scanner: BleScanner) {

    /** Emits every accepted anchor sighting. */
    val observations: Flow<BeaconObservation>

    /** Emits region enter/exit. On Android these are synthesised; on iOS they come from the OS. */
    val transitions: Flow<ZoneTransition>

    val isWitnessing: Flow<Boolean>

    /**
     * Begin monitoring and ranging the anchors described by [plan]. Returns a failure when the
     * platform refuses — missing Always authorization on iOS, missing scan permission on Android,
     * or an unconfigured plan.
     */
    suspend fun start(plan: BeaconPlan): Result<Unit>

    fun stop()

    /**
     * Whether the platform is in a state where witnessing would actually survive backgrounding.
     * Surfaced in the lab console so the operator sees a refusal rather than silence.
     */
    fun residencyDiagnostics(): List<String>
}

/**
 * Turns a stream of [BeaconObservation] into zone transitions with hysteresis.
 *
 * Hysteresis matters more than it looks. A participant standing near a zone boundary will
 * otherwise generate a burst of enter/exit pairs as RSSI fluctuates by a few dB, and those false
 * transitions land directly in the ground-truth record. Requiring [enterSamples] consecutive
 * sightings to enter and [exitTimeoutMillis] of silence to leave costs a little latency and buys a
 * truth channel that does not chatter.
 */
class ZoneTracker(
    private val plan: BeaconPlan,
    private val enterSamples: Int = 3,
    private val exitTimeoutMillis: Long = 15_000,
) {
    private data class Candidate(var hits: Int, var lastWallMillis: Long, var inside: Boolean)

    private val state = mutableMapOf<String, Candidate>()

    /** Feed one observation; returns a transition when the tracked state actually changed. */
    fun observe(observation: BeaconObservation): ZoneTransition? {
        val key = observation.anchorKey
        val candidate = state.getOrPut(key) { Candidate(0, observation.wallMillis, inside = false) }
        candidate.hits++
        candidate.lastWallMillis = observation.wallMillis

        if (!candidate.inside && candidate.hits >= enterSamples) {
            candidate.inside = true
            return ZoneTransition(
                zone = plan.zone(observation.major, observation.minor),
                major = observation.major,
                minor = observation.minor,
                entered = true,
                monotonicNanos = observation.monotonicNanos,
                wallMillis = observation.wallMillis,
            )
        }
        return null
    }

    /** Call periodically; emits exits for anchors that have gone quiet. */
    fun sweep(nowWallMillis: Long, nowMonotonicNanos: Long): List<ZoneTransition> {
        val exits = mutableListOf<ZoneTransition>()
        for ((key, candidate) in state) {
            if (!candidate.inside) continue
            if (nowWallMillis - candidate.lastWallMillis < exitTimeoutMillis) continue
            candidate.inside = false
            candidate.hits = 0
            val major = key.substringBefore('/').toIntOrNull() ?: continue
            val minor = key.substringAfter('/').toIntOrNull() ?: continue
            exits += ZoneTransition(
                zone = plan.zone(major, minor),
                major = major,
                minor = minor,
                entered = false,
                monotonicNanos = nowMonotonicNanos,
                wallMillis = nowWallMillis,
            )
        }
        return exits
    }

    fun currentZones(): List<String> = state.filterValues { it.inside }.keys.toList()

    fun reset() = state.clear()
}
