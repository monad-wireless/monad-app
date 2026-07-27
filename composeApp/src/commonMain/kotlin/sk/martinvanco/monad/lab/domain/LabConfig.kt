package sk.martinvanco.monad.lab.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The lab bundle, served by the backend at `GET /api/lab/config`.
 *
 * Everything the instrument needs in order to play its roles comes from here — SSIDs, the
 * collector endpoint, the beacon UUID and its zone map, the traffic profiles an experiment may
 * command. Nothing about a specific deployment is compiled into the app; swapping rooms or moving
 * the collector is a config change, not a release.
 */
@Serializable
data class LabConfig(
    /** Monotonically increasing; the app caches by version and refetches when the server bumps it. */
    val version: Int = 0,
    /** PostGIS site slug this bundle describes, e.g. `fiit-library-floor-0`. */
    val site: String = "",
    val collector: CollectorEndpoint = CollectorEndpoint(),
    @SerialName("access_points") val accessPoints: List<ApProfile> = emptyList(),
    val beacons: BeaconPlan = BeaconPlan(),
    @SerialName("traffic_profiles") val trafficProfiles: List<TrafficProfile> = emptyList(),
    @SerialName("clock_sync") val clockSync: ClockSyncPolicy = ClockSyncPolicy(),
) {
    fun accessPoint(id: String): ApProfile? = accessPoints.firstOrNull { it.id == id }

    fun trafficProfile(id: String): TrafficProfile? = trafficProfiles.firstOrNull { it.id == id }

    /** True when the bundle carries enough to run an illuminator session. */
    val isIlluminationReady: Boolean
        get() = collector.host.isNotBlank() && collector.udpPort in 1..65535 && accessPoints.isNotEmpty()

    companion object {
        /**
         * Offline fallback so the debug console is usable on a bench with no backend. Deliberately
         * points at nothing routable — the operator fills it in from the console.
         */
        val EMPTY = LabConfig()
    }
}

/**
 * The collector — clock master and sink for the emitted stream. One host, one UDP port: the data
 * stream and the time-sync exchange deliberately share a socket so the offset is measured over the
 * exact path the data takes.
 */
@Serializable
data class CollectorEndpoint(
    val host: String = "",
    @SerialName("udp_port") val udpPort: Int = 9999,
    /** Optional HTTP base for the coarse time fallback; empty means "use the API base URL". */
    @SerialName("http_base") val httpBase: String = "",
)

@Serializable
data class ApProfile(
    val id: String = "",
    val ssid: String = "",
    /** `open` | `wpa2` | `wpa3` | `wep`. */
    val security: String = "open",
    val password: String? = null,
    /** `2.4` | `5` | `6` — informational, recorded into the session sidecar. */
    val band: String = "",
    val channel: Int? = null,
    /** Which fleet node hosts this AP, for provenance. */
    @SerialName("host_node") val hostNode: String = "",
)

/**
 * The anchor plan. Anchors advertise as iBeacons because that is the only anchor format a
 * backgrounded — or terminated — iOS app can be woken by. `major` groups a floor or zone cluster,
 * `minor` identifies the individual anchor.
 */
@Serializable
data class BeaconPlan(
    /** The lab's proximity UUID, shared by every anchor. Empty disables witnessing. */
    val uuid: String = "",
    /** Majors to monitor as CoreLocation regions. Empty means "monitor the UUID as one region". */
    val majors: List<Int> = emptyList(),
    val zones: List<BeaconZone> = emptyList(),
) {
    fun zone(major: Int, minor: Int): BeaconZone? =
        zones.firstOrNull { it.major == major && it.minor == minor }

    val isConfigured: Boolean get() = uuid.isNotBlank()
}

@Serializable
data class BeaconZone(
    val major: Int = 0,
    val minor: Int = 0,
    /** PostGIS cell id this anchor sits in. */
    @SerialName("cell_id") val cellId: String = "",
    val label: String = "",
    /** Surveyed position in the site's CRS, metres. Null when not yet surveyed. */
    val x: Double? = null,
    val y: Double? = null,
    val floor: String = "",
)

/**
 * A commanded emission profile. `rateHz` is the *commanded* pace — the whole point of the
 * illuminator contract is that the delivered pace is measured separately and may differ.
 */
@Serializable
data class TrafficProfile(
    val id: String = "",
    val label: String = "",
    @SerialName("rate_hz") val rateHz: Double = 25.0,
    @SerialName("payload_bytes") val payloadBytes: Int = 200,
    @SerialName("duration_seconds") val durationSeconds: Int = 300,
    /** AP this profile expects to be associated to; empty means "whatever is current". */
    @SerialName("ap_id") val apId: String = "",
) {
    val isValid: Boolean
        get() = rateHz > 0.0 && rateHz <= 1000.0 && payloadBytes in 0..1400 && durationSeconds > 0
}

@Serializable
data class ClockSyncPolicy(
    /** Exchanges per burst; the minimum-delay sample of the burst is the estimate. */
    @SerialName("burst_size") val burstSize: Int = 20,
    /** Gap between exchanges inside a burst. */
    @SerialName("burst_spacing_ms") val burstSpacingMs: Long = 50,
    /** How often to re-run a burst during a long session. */
    @SerialName("resync_seconds") val resyncSeconds: Int = 600,
    /** Per-exchange timeout. */
    @SerialName("timeout_ms") val timeoutMs: Long = 1_000,
)
