package sk.martinvanco.monad.lab.presentation

import sk.martinvanco.monad.lab.data.LabConfigService
import sk.martinvanco.monad.lab.domain.BroadcastReport
import sk.martinvanco.monad.lab.domain.ClockEstimate
import sk.martinvanco.monad.lab.domain.MeshProgress
import sk.martinvanco.monad.lab.domain.InstrumentLogLine
import sk.martinvanco.monad.lab.domain.LabConfig
import sk.martinvanco.monad.lab.domain.LabInstrumentState
import sk.martinvanco.monad.lab.domain.PoseTrackProgress
import sk.martinvanco.monad.lab.domain.PoseTrackReport
import sk.martinvanco.monad.lab.domain.ResidencyCheck
import sk.martinvanco.monad.lab.domain.TrackingQuality
import sk.martinvanco.monad.lab.domain.health.InstrumentHealth
import sk.martinvanco.monad.lab.domain.preflight.PreflightReport
import sk.martinvanco.monad.lab.domain.upload.FlushReport

/**
 * State of the lab console — the operator's surface for running a **walk**.
 *
 * ### What this console is, and what it stopped being
 *
 * It was the operator surface for one experiment: the staged EXP-P3 people-counting session, thirteen
 * panels deep, ordered by that session's start-up gates. Four of those panels drove the illuminator
 * role — collector endpoint, traffic profile, emitted-versus-commanded rate, and the UDP clock
 * exchange — and **none of them can run on this deployment**. The fleet's AX210 cannot enter AP mode
 * (the Intel LAR firmware limit), so there is nothing for the phone to associate to, therefore no
 * collector, no pinned socket and no clock burst. Three more panels drove the staircase design's
 * blocks and its room-wide people tally, which are meaningless for one person walking alone.
 *
 * A console where seven of thirteen panels describe an impossible session is not a dense instrument,
 * it is a maze. So this one is scoped to the session the hardware can actually run:
 *
 *  * the phone **advertises** a session identity the fleet's passive BLE scan can hear,
 *  * the phone **records where it was** while it did (visual-inertial odometry),
 *  * the operator **marks surveyed waypoints** that tie the two frames together.
 *
 * The illuminator machinery is not deleted — [sk.martinvanco.monad.lab.domain.TrafficGenerator] and
 * the clock service are untouched and still reachable from a quest — it simply has no operator panel
 * while it has no access point to run against.
 *
 * ### What the panels are still for
 *
 * The original console's justification survives verbatim: every failure mode of this instrument is
 * silent. A revoked authorization leaves the app running. iOS backgrounding moves the identity frame
 * into an overflow area no node in the room can parse, with no error anywhere. Odometry that has lost
 * tracking keeps returning plausible positions. None of those three announce themselves, and all
 * three produce a session that looks complete and is worthless. The console exists to make them
 * visible on a bench in seconds.
 */
data class LabConsoleState(
    val config: LabConfig = LabConfig.EMPTY,
    val configSource: LabConfigService.Source = LabConfigService.Source.NONE,
    val instrument: LabInstrumentState = LabInstrumentState.IDLE,
    val residency: List<ResidencyCheck> = emptyList(),
    /** Platform posture of the odometry tracker. Answerable with nothing running. */
    val trackerDiagnostics: List<String> = emptyList(),
    /** Platform posture of the BLE advertiser. Answerable with nothing running. */
    val broadcastDiagnostics: List<String> = emptyList(),
    /**
     * Advertise namespace typed by hand, so a bench rig works before the backend knows it exists.
     *
     * The one manual override this console keeps. The collector host and port it used to offer are
     * gone with the illuminator panels — there is no collector to point at — and the beacon UUID went
     * with the anchor panel. The namespace stays because it is the single value that decides whether
     * the fleet can attribute anything it hears to this handset.
     */
    val manualAdvertiseNamespace: String = "",
    val log: List<InstrumentLogLine> = emptyList(),
    val sessions: List<SessionRow> = emptyList(),
    val unsyncedCount: Long = 0,
    val isBusy: Boolean = false,
    val message: String? = null,

    // ---- what the next walk will do ----------------------------------------------------
    /**
     * Put the session identity frame on air for the whole walk.
     *
     * On by default, and it is the reason the walk exists: without it the fleet records channel state
     * with nothing in it to attribute to this handset. The previous console could not switch this on
     * at all — `SessionRequest.broadcast` defaulted to false and the start path never set it — so the
     * only way a phone advertised was inside a quest with a `ble_advertise` step.
     */
    val broadcastEnabled: Boolean = true,
    /** Record the trajectory. On by default on a platform that has odometry. */
    val trackEnabled: Boolean = true,
    /**
     * Relocalise into the site's saved world map, when one exists.
     *
     * Off is a deliberate fresh origin — the right call on a bench, at home, or after the room
     * changed. The tracker abandons a hopeless map on its own after a grace period, so leaving
     * this on costs at most that grace; turning it off skips the wait when the operator already
     * knows the map is wrong.
     */
    val useSiteMap: Boolean = true,
    /**
     * Monitor the ESP32 iBeacon anchors.
     *
     * Off unless the bundle carries an anchor plan, because without deployed anchors it produces an
     * empty stream and a health monitor watching a stream that cannot exist.
     */
    val witnessEnabled: Boolean = false,
    val trackRateHz: Double = DEFAULT_TRACK_RATE_HZ,

    // ---- live -------------------------------------------------------------------------
    val isBroadcasting: Boolean = false,
    /** What the platform accepted, not what was commanded. Null until it goes on air. */
    val broadcastReport: BroadcastReport? = null,
    val poseProgress: PoseTrackProgress = PoseTrackProgress.IDLE,
    val poseReport: PoseTrackReport? = null,
    /**
     * What the room scan has found.
     *
     * Its own panel, because a mesh that stops growing halfway through a walk is the one geometry
     * failure that is cheap to fix while still in the room and impossible to fix afterwards.
     */
    val mesh: MeshProgress = MeshProgress.IDLE,
    /**
     * The clock estimate mapping this device's `mono_ns` onto the reference epoch.
     *
     * On the console because it is the link that makes everything else joinable: without it the
     * trajectory, the mesh and the identity frame are all on a device-local timeline with an arbitrary
     * origin, and the fleet's CSI cannot be lined up with any of them.
     */
    val clock: ClockEstimate = ClockEstimate.UNSYNCED,
    /**
     * Per-stream liveness. On this console it earns its place through the pose stream: a commanded
     * rate is the only thing that turns "odometry quietly stopped" into a number.
     */
    val health: InstrumentHealth = InstrumentHealth.IDLE,
    /** Monotonic nanoseconds as of the last display tick — drives the elapsed-time readout. */
    val nowMonotonicNanos: Long = 0,

    // ---- waypoints --------------------------------------------------------------------
    /**
     * Which numbered fingerprint card the operator is about to record.
     *
     * A stepper rather than a camera scan, and that is a hardware constraint rather than a UI
     * preference: ARKit holds the camera for the whole walk, and opening a QR scanner beside it
     * interrupts one of the two capture sessions. Pausing the tracker to scan would put a hole in the
     * trajectory at exactly the instant the waypoint is supposed to anchor it — which destroys the
     * correspondence the waypoint exists to provide. So while tracking, waypoints are typed or tapped.
     */
    val waypointPoint: Int = 1,
    /** Free-text code, for the named zone cards that are not in the numbered pool. */
    val waypointCode: String = "",
    /**
     * The card the tracker's camera can read right now, or null.
     *
     * Sampled on the display tick from the instrument, never collected — see
     * `LabInstrument.seenCard`. Null covers "nothing in view" and "this device cannot see cards"
     * identically, because the console does the same thing in both: fall back to the manual code.
     */
    val detectedCard: String? = null,
    /** Codes recorded during this session, newest first. One tap re-records a revisited point. */
    val waypoints: List<WaypointRow> = emptyList(),
    /** True while the inline QR scanner is open. Only reachable when the tracker is not running. */
    val isScanning: Boolean = false,

    /**
     * The card the operator is currently standing on, when a dwell is open.
     *
     * A dwell is the stationary probe arm: body parked on a surveyed card, held, so the CSI
     * statistic reads against a fixed position with no direction-of-motion confound. It is the arm
     * that can settle the walked-vs-simulated sign disagreement, and it costs two taps.
     */
    val dwellCode: String? = null,
    /** When the open dwell started, for the elapsed readout and the closing marker's duration. */
    val dwellStartedMonotonicNanos: Long = 0,

    /** Camera preview on the walk panel. Costs battery; the coaching it buys usually wins. */
    val showCameraPreview: Boolean = true,

    /**
     * A close the operator has to dismiss, or null when stopping needs no ceremony.
     *
     * Both 2026-08-19 walks closed with zero waypoints, which left two trajectories tied to no
     * building — and nothing said so until analysis. The warning is dismissable rather than a hard
     * refusal: an aborted bench test should not have to invent three waypoints to end.
     */
    val stopWarning: String? = null,

    // ---- pre-flight -------------------------------------------------------------------
    val preflight: PreflightReport? = null,
    val preflightRunning: Boolean = false,
    /** The last upload flush, so a failure is visible without opening the session list. */
    val lastFlush: FlushReport? = null,
) {
    val isRunning: Boolean get() = instrument.isRunning
    val residencyBlockers: List<ResidencyCheck> get() = residency.filterNot { it.satisfied }

    /** Session elapsed time as of the last display tick. */
    val elapsedMillis: Long
        get() = if (isRunning && instrument.startedMonotonicNanos > 0) {
            (nowMonotonicNanos - instrument.startedMonotonicNanos).coerceAtLeast(0) / 1_000_000
        } else {
            0
        }

    /** The bundle's advertise namespace, or null when this deployment has none. */
    val advertiseNamespace: String? get() = config.advertise.namespaceUuid.takeIf { it.isNotBlank() }

    /**
     * True when there are anchors to witness — a UUID **and** at least one surveyed zone.
     *
     * Stricter than `BeaconPlan.isConfigured`, which checks only the UUID, and deliberately so. The
     * deployed bundle carries a UUID with `zones: []` and its own comment says that is meant to disable
     * witnessing "cleanly rather than half-enabling it" — but a UUID alone passes `isConfigured`, so the
     * instrument would start a scan whose observations map to no zone. Rows that name no place are not a
     * quieter witness stream, they are an unattributable one.
     *
     * The domain contract is left alone: a quest may still ask for witnessing on a UUID-only bundle, and
     * raw RSSI against a known UUID is legitimate data for something that knows what to do with it. This
     * is about what the console offers an operator, and a toggle that yields unplaceable rows is a
     * toggle that costs a session to discover.
     *
     * Note that a walk does not need anchors at all. The fleet's own BLE scan hears the phone, which is
     * the opposite direction and the stronger one — see the broadcast panel.
     */
    val witnessAvailable: Boolean
        get() = config.beacons.isConfigured && config.beacons.zones.isNotEmpty()

    /**
     * Whether the tracker can run here, as far as the platform has said.
     *
     * Derived from the diagnostics rather than from a probe, because this is read on every recompose
     * and a probe is a suspend call. The diagnostics are refreshed when the console opens.
     */
    val trackerAvailable: Boolean
        get() = trackerDiagnostics.none {
            val lower = it.lowercase()
            lower.contains("not supported") || lower.contains("not implemented") ||
                lower.contains("missing")
        } && trackerDiagnostics.isNotEmpty()

    /** True once the clock has been disciplined at least once. */
    val clockSynced: Boolean get() = clock.samples > 0

    /**
     * The one sentence that says whether this walk is producing anything.
     *
     * Three separate silent failures collapse into it, in the order that matters: nothing on air
     * beats nothing tracking, because a walk with a trajectory and no identity frame cannot be joined
     * to any fleet capture at all, while a walk with an identity frame and no trajectory still yields
     * scanned points.
     */
    val walkHeadline: String
        get() = when {
            !isRunning -> "not walking"
            broadcastEnabled && !isBroadcasting -> "NOT ON AIR — the fleet cannot hear this phone"
            !clockSynced -> "recording, but UNPLACEABLE — no clock sample yet"
            trackEnabled && poseReport == null -> "on air, not tracking — waypoints only"
            trackEnabled && poseProgress.quality != TrackingQuality.NORMAL ->
                "on air, tracking ${poseProgress.quality.wire.uppercase()}" +
                    (poseProgress.last?.reason?.let { " ($it)" } ?: "")

            else -> "on air and tracking"
        }

    /**
     * True when the walk is producing what it was asked to produce. Drives one colour, nothing else.
     */
    val walkHealthy: Boolean
        get() = isRunning &&
            (!broadcastEnabled || isBroadcasting) &&
            (!trackEnabled || poseProgress.quality == TrackingQuality.NORMAL)

    /**
     * The one sentence telling the operator what to change with their hands, right now.
     *
     * Evidence-ordered, most fixable first. Camera pitch leads because it is what separated the two
     * 2026-08-19 walks (−39° carried, 35 % normal; −14° carried, 61 %) and because raising a phone
     * is the cheapest fix in the whole instrument. Null when there is nothing to coach — silence is
     * what lets a sentence, when it appears, be read.
     */
    val coaching: String?
        get() {
            if (!isRunning || !trackEnabled || poseReport == null) return null
            val progress = poseProgress
            val pitch = progress.pitchDegrees
            val reason = progress.last?.reason
            return when {
                pitch != null && pitch < PITCH_FLOOR_DEGREES ->
                    "RAISE THE PHONE — the camera is looking at the floor " +
                        "(${pitch.toInt()}°). Hold it up so the preview shows the room ahead."

                reason == "initializing" && elapsedMillis > INIT_GRACE_MILLIS ->
                    "Tracking cannot initialise. Stand still a moment and point the camera at " +
                        "furniture or shelving a few metres away, not at a blank wall."

                reason == "insufficient_features" ->
                    "The camera sees too little texture. Aim at furniture, shelving or posters — " +
                        "not carpet, glass or a bare wall."

                reason == "excessive_motion" ->
                    "Moving too fast for the tracker. Slow down and keep the phone steady."

                reason == "relocalizing" ->
                    "Relocalising. Stand still and face somewhere already scanned until quality " +
                        "reads NORMAL."

                progress.samples > 0 && progress.rejectedJumps * 20 > progress.samples ->
                    "The tracker keeps re-solving its world. Hold the phone up and forward with a " +
                        "clear view several metres ahead."

                else -> null
            }
        }

    /**
     * The code the waypoint button will record.
     *
     * PRECEDENCE, AND WHY IT IS THIS WAY ROUND. What the camera can read wins, because the operator
     * is standing in front of that card and pointing the phone at it — that is a stronger statement
     * of intent than a stepper left where the last card put it. The typed field comes next, so the
     * named zone cards (`MONAD-A-IN`, …) stay reachable. The numbered stepper is the floor.
     *
     * The stepper used to be the default and the camera was not an input at all: scanning opened a
     * second capture session, which contends with ARKit, so the console refused it whenever a walk
     * was tracking. Twenty cards were therefore dialled in by hand. The decode now rides ARKit's
     * own frames, so the natural gesture — look at the card — is the one that works.
     */
    val pendingWaypointCode: String
        get() = detectedCard?.let(::waypointCodeFrom)?.takeIf { it.isNotBlank() }
            ?: waypointCode.trim().ifBlank { fingerprintCode(waypointPoint) }

    /** True when the pending code came from the camera rather than from a control. */
    val pendingCameFromCamera: Boolean
        get() = detectedCard?.let(::waypointCodeFrom)?.isNotBlank() == true

    /** True while the operator is standing a dwell on a card. */
    val isDwelling: Boolean get() = dwellCode != null

    /**
     * What stopping this walk right now would silently cost, or null when it costs nothing.
     *
     * Pure and derived, so the gate is testable without a screen model. The waypoint floor is
     * three — the transform into the site frame plus a drift bound — but the sentence adjusts:
     * a walk that relocalised into a saved site map is already in the site frame, and waypoints
     * then verify rather than establish it.
     */
    val stopWarningText: String?
        get() {
            if (!isRunning || !trackEnabled || poseReport == null) return null
            val complaints = mutableListOf<String>()
            if (waypoints.size < WAYPOINT_FLOOR) {
                complaints += "only ${waypoints.size} of $WAYPOINT_FLOOR waypoints are recorded — " +
                    "the trajectory cannot be tied to the building and its drift cannot be bounded"
            }
            val trusted = poseProgress.normalFraction
            if (trusted != null && poseProgress.samples >= TRUST_SAMPLE_FLOOR && trusted < TRUST_FLOOR) {
                complaints += "only ${(trusted * 100).toInt()} % of the track is trusted — " +
                    "re-taking the walk now is cheaper than discarding it in analysis"
            }
            if (complaints.isEmpty()) return null
            return "Stopping now: " + complaints.joinToString(", and ") + "."
        }

    companion object {
        /**
         * Default pose rate.
         *
         * 10 Hz. A body walking at 1.4 m/s moves 14 cm between samples, which is finer than the error
         * odometry accumulates across a room, and one sixth of the rows the camera's own 60 Hz frame
         * rate would write. Raising it buys resolution the position error has already spent.
         */
        const val DEFAULT_TRACK_RATE_HZ: Double = 10.0

        /** Rates the console offers. Above 60 Hz polling returns the same frame twice. */
        val TRACK_RATE_OPTIONS: List<Double> = listOf(2.0, 5.0, 10.0, 20.0)

        /**
         * How many numbered fingerprint cards exist.
         *
         * **Mirrors `infra/labels/markers.toml` in monad-knowledge**, which prints them, and is a
         * mirror rather than a source: the card on the wall is the contract, and this app is one of
         * two things that has to agree with it. Twenty cards, `MONAD-FP-01` through `MONAD-FP-20`.
         *
         * Properly this belongs in the lab bundle, so re-printing the set could not desynchronise the
         * app. It is here because the bundle does not carry a marker list yet, and a hardcoded
         * template that is written down as a mirror is better than a hardcoded list that pretends to
         * be authoritative.
         */
        const val FINGERPRINT_CARD_COUNT: Int = 20

        /**
         * Below this smoothed camera pitch the console tells the operator to raise the phone.
         *
         * −30°. Walk A's median was −39° (35 % normal); walk B's −14° (61 %). The threshold sits
         * between them, on the failing side, so a phone carried console-first trips it and a phone
         * held preview-first does not.
         */
        const val PITCH_FLOOR_DEGREES = -30.0

        /** How long `initializing` is normal before it becomes a coaching sentence. */
        const val INIT_GRACE_MILLIS = 10_000L

        /** Waypoints needed for a site-frame transform plus a drift bound. */
        const val WAYPOINT_FLOOR = 3

        /** Trusted-fraction floor mirrored from the instrument's close-time callout. */
        const val TRUST_FLOOR = 0.8

        /** Samples before the trusted fraction is worth warning about. ~30 s at the default rate. */
        const val TRUST_SAMPLE_FLOOR = 300L

        /** The printed slug of numbered card [point]. See [FINGERPRINT_CARD_COUNT]. */
        fun fingerprintCode(point: Int): String =
            "MONAD-FP-" + point.coerceIn(1, FINGERPRINT_CARD_COUNT).toString().padStart(2, '0')

        /**
         * Pull the card slug out of whatever the camera decoded.
         *
         * The printed QR carries `https://monad.dubec.dev/m/<slug>` so that a scan works for somebody
         * who has not installed the app. The slug is what the placement record names, so the slug is
         * what a waypoint records — a full URL in the payload would make every analysis strip the same
         * prefix, and one of them would forget.
         */
        fun waypointCodeFrom(scanned: String): String {
            val trimmed = scanned.trim()
            val marker = "/m/"
            val index = trimmed.lastIndexOf(marker)
            if (index < 0) return trimmed
            return trimmed.substring(index + marker.length)
                .substringBefore('?')
                .substringBefore('#')
                .trim()
                .ifBlank { trimmed }
        }
    }
}

/** One recorded waypoint, for the console's list. */
data class WaypointRow(
    val code: String,
    val wallMillis: Long,
    /** Null when nothing was tracking — a real answer, not a gap. See `WaypointMarkerPayload`. */
    val x: Float?,
    val z: Float?,
    val quality: TrackingQuality,
)

data class SessionRow(
    val sessionId: String,
    val status: String,
    val startedWallMillis: Long,
    val participantId: String,
    val socketPinned: Boolean,
    val boundInterface: String,
    val uploadError: String?,
)

sealed interface LabConsoleEvent {
    data object RefreshConfig : LabConsoleEvent
    data object StartSession : LabConsoleEvent
    data object StopSession : LabConsoleEvent

    /** The operator read the stop warning and wants to stop anyway. */
    data object ConfirmStopSession : LabConsoleEvent

    /** The operator read the stop warning and is going back to fix what it names. */
    data object DismissStopWarning : LabConsoleEvent

    /** Toggle the walk panel's camera preview. */
    data class ToggleCameraPreview(val shown: Boolean) : LabConsoleEvent

    /** Toggle relocalising into the saved site map for the next walk. */
    data class ToggleSiteMap(val enabled: Boolean) : LabConsoleEvent

    // ---- dwell ------------------------------------------------------------------------
    /** Start standing a dwell on the pending waypoint card. Records the waypoint too. */
    data object StartDwell : LabConsoleEvent

    /** Finish the open dwell. */
    data object EndDwell : LabConsoleEvent
    data object RequestPrerequisites : LabConsoleEvent
    data object RetryUploads : LabConsoleEvent
    data object ClearLog : LabConsoleEvent
    data class DeleteSession(val sessionId: String) : LabConsoleEvent

    // ---- what the next walk will do ----------------------------------------------------
    data class ToggleBroadcast(val enabled: Boolean) : LabConsoleEvent
    data class ToggleTrack(val enabled: Boolean) : LabConsoleEvent
    data class ToggleWitness(val enabled: Boolean) : LabConsoleEvent
    data class SelectTrackRate(val rateHz: Double) : LabConsoleEvent

    /**
     * Put the identity frame on or off air without stopping the session.
     *
     * Separate from the session toggle because they are separate facts on the timeline: a
     * `broadcast_start` / `broadcast_stop` pair inside one recording is how a walk can carry an arm
     * with the phone silent, against the same trajectory and the same room.
     */
    data object StartBroadcast : LabConsoleEvent
    data object StopBroadcast : LabConsoleEvent

    // ---- waypoints --------------------------------------------------------------------
    data class SelectWaypointPoint(val point: Int) : LabConsoleEvent
    data class UpdateWaypointCode(val value: String) : LabConsoleEvent

    /** Record [code], or [LabConsoleState.pendingWaypointCode] when null. */
    data class MarkWaypoint(val code: String? = null) : LabConsoleEvent
    data object StartWaypointScan : LabConsoleEvent
    data object StopWaypointScan : LabConsoleEvent
    data class WaypointScanned(val raw: String) : LabConsoleEvent

    // ---- pre-flight --------------------------------------------------------------------
    data object RunPreflight : LabConsoleEvent

    /** Set the advertise namespace by hand, so a bench rig works before the backend knows it exists. */
    data class UpdateAdvertiseNamespace(val value: String) : LabConsoleEvent
    data object ApplyAdvertiseNamespace : LabConsoleEvent
}
