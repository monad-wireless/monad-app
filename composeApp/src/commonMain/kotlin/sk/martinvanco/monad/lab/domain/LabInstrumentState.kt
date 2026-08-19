package sk.martinvanco.monad.lab.domain

/**
 * The instrument's inputs and observable state.
 *
 * Split out of `LabInstrument.kt` so the file that holds the start-up order holds only that. These
 * are plain values with no behaviour: what a session was asked to do ([SessionRequest]), how far it
 * has got ([Phase], [LabInstrumentState]), and what it has said ([InstrumentLogLine]).
 */

/** Everything the instrument needs to run one session. Built from [LabConfig] by the caller. */
data class SessionRequest(
    val participantId: String,
    val collector: CollectorEndpoint,
    val beacons: BeaconPlan = BeaconPlan(),
    val accessPoint: ApProfile? = null,
    val trafficProfile: TrafficProfile? = null,
    val clockSync: ClockSyncPolicy = ClockSyncPolicy(),
    val site: String = "",
    val configVersion: Int = 0,
    val enrollmentId: String? = null,
    val questId: String? = null,
    /** Play the illuminator role: associate, pin, sync, emit. */
    val emit: Boolean = true,
    /** Play the witness role: monitor and range the anchors. */
    val witness: Boolean = true,
    /** Play the broadcaster role for the whole session: advertise the session identity frame. */
    val broadcast: Boolean = false,
    /** The bundle's advertise plan; the broadcaster refuses when it is not configured. */
    val advertise: AdvertisePlan = AdvertisePlan(),
    /**
     * Record the phone's own trajectory for the length of the session (visual-inertial odometry).
     *
     * Off by default, because it costs the camera, keeps the screen awake, and is meaningless for a
     * pocketed witness-only participant. On for a fingerprinting walk, where it is the point.
     */
    val track: Boolean = false,
    /**
     * Commanded pose rate. 10 Hz by default: a walking body covers about 14 cm between samples at
     * that rate, which is finer than the position error odometry accumulates over a room, and one
     * sixth of the rows the camera's own frame rate would produce.
     */
    val trackRateHz: Double = 10.0,
)

enum class Phase { IDLE, STARTING, ASSOCIATING, BINDING, SYNCING, RUNNING }

data class LabInstrumentState(
    val sessionId: String?,
    val phase: Phase,
    val startedWallMillis: Long,
    val startedMonotonicNanos: Long,
    val boundInterface: String,
    val socketPinned: Boolean,
    val beaconCount: Long,
    val lastRssi: Int?,
    val currentZones: List<String>,
    val lastError: String?,
    val request: SessionRequest?,
) {
    val isRunning: Boolean get() = phase == Phase.RUNNING

    companion object {
        val IDLE = LabInstrumentState(
            sessionId = null,
            phase = Phase.IDLE,
            startedWallMillis = 0,
            startedMonotonicNanos = 0,
            boundInterface = "",
            socketPinned = false,
            beaconCount = 0,
            lastRssi = null,
            currentZones = emptyList(),
            lastError = null,
            request = null,
        )
    }
}

data class InstrumentLogLine(val wallMillis: Long, val message: String)
