package sk.martinvanco.monad.lab.domain.preflight

import sk.martinvanco.monad.core.domain.permissions.PermissionStatus
import sk.martinvanco.monad.lab.domain.BuildDiagnostics
import sk.martinvanco.monad.lab.domain.ClockEstimate
import sk.martinvanco.monad.lab.domain.ClockGate
import sk.martinvanco.monad.lab.domain.ClockGateStatus
import sk.martinvanco.monad.lab.domain.LabConfig
import sk.martinvanco.monad.lab.domain.ResidencyCheck
import sk.martinvanco.monad.lab.domain.TelemetryPosture
import sk.martinvanco.monad.lab.domain.roundTo

/**
 * The pre-flight readiness check.
 *
 * It exists to answer one question before ten people are standing in a room: **would this phone
 * produce data the pre-registration accepts?** Every failure mode this instrument has is silent —
 * an unpinned socket still "sends", a revoked authorization still leaves the app running in the
 * foreground, a collector that cannot be reached still lets a session start and record beautifully
 * timestamped nonsense. All of those are discoverable in seconds at a bench and expensive to
 * discover at 10:05 on Day 1.
 *
 * The evaluation is pure. Gathering the inputs is somebody else's problem (`PreflightService`), so
 * every criterion here can be checked at its boundary without a radio.
 *
 * ### The criteria, and why each one is where it is
 *
 * | Check | FAIL when | WARN when |
 * |---|---|---|
 * | Permissions | a **required** [PermissionStatus] is not granted | none were supplied to check |
 * | Residency | any platform residency check is unsatisfied | — |
 * | Config | the bundle cannot run an illuminator session | no beacon plan (witness disabled) |
 * | Collector | the probe could not reach it | best RTT above [RTT_WARN_MILLIS] |
 * | Clock gate | fewer than two sync samples, or residual past G4a | residual past G4b (T3 drops) |
 * | Storage | free space below the session estimate | below twice the estimate |
 * | Room scan | — | this phone has LiDAR and the mesh is not being exported |
 * | Telemetry | — | no collector endpoint, so the walk is invisible while it runs |
 * | Backlog | — | anything still unsent from a previous session |
 * | Build diagnostics | any Xcode interception shim is switched on | — |
 *
 * A FAIL is a blocker; a WARN is something to know. Nothing here refuses to let the operator start
 * a session — the instrument is theirs, and a deliberate degraded run is sometimes the right call.
 * What it removes is the *unknowing* degraded run.
 */
object Preflight {

    /**
     * Above this the link is slow enough that the minimum-delay filter has little to choose from.
     *
     * Not a failure: G4b's budget is 250 ms and a 250 ms RTT still leaves a usable offset once the
     * filter has picked the best exchange of a burst. It is worth saying out loud, because it is
     * usually a symptom of something else (an AP under load, a socket that is not on the Wi-Fi).
     */
    const val RTT_WARN_MILLIS: Double = 250.0

    /** Bytes a traffic row costs on disk: the TSV row plus its SQLite row and index overhead. */
    const val BYTES_PER_TRAFFIC_ROW: Long = 110

    /**
     * Everything that is not the traffic stream: beacons, transitions, markers, health checkpoints,
     * ground truth, the sidecar, and the copy the uploader renders before sending. Flat, because
     * none of them scale with the commanded rate and guessing each one precisely would be false
     * precision.
     */
    const val FIXED_SESSION_BYTES: Long = 64L * 1024 * 1024

    /** The planned staged session: 180 min per §2 of the pre-registration. */
    const val PLANNED_SESSION_SECONDS: Int = 3 * 60 * 60

    /**
     * What one session will cost on disk.
     *
     * Deliberately generous. Running out of storage mid-session is not a degraded run, it is a lost
     * one — SQLite starts failing inserts and the streams silently stop — so the estimate is allowed
     * to be wrong in the direction that makes the operator free up space they did not need.
     */
    fun estimateSessionBytes(
        commandedRateHz: Double,
        seconds: Int = PLANNED_SESSION_SECONDS,
    ): Long {
        val rows = (commandedRateHz.coerceAtLeast(0.0) * seconds.coerceAtLeast(0)).toLong()
        return rows * BYTES_PER_TRAFFIC_ROW + FIXED_SESSION_BYTES
    }

    /**
     * Judge readiness for the session the operator is about to run.
     *
     * **The intent is load-bearing, not cosmetic.** Before it existed every check ran on every
     * session, so a fingerprinting walk — which needs no access point, no collector and no UDP clock
     * exchange, because the fleet's AX210 cannot run AP mode at all — reported three hard blockers
     * and a "NOT READY" headline every single time. A readiness display that is always red is not a
     * readiness display, it is noise the operator learns to tap past, and the one genuine blocker
     * hiding among the three would go past with it.
     *
     * So the checks a mode cannot pass are not softened, they are **not asked**. An omitted check is
     * visible as an omission: the report carries only what applies, and the intent is named on it.
     */
    fun evaluate(inputs: PreflightInputs): PreflightReport {
        val checks = buildList {
            add(permissions(inputs))
            add(residency(inputs))
            add(config(inputs))
            if (inputs.intent == SessionIntent.ILLUMINATE) {
                add(collector(inputs))
                add(clockGate(inputs))
            }
            if (inputs.intent == SessionIntent.WALK) {
                add(advertise(inputs))
                add(tracker(inputs))
                add(roomScan(inputs))
                // Asked for a walk too, because a walk that cannot be placed on the fleet's timeline is
                // a trajectory and a mesh of nowhen. It was not asked before the reference-clock path
                // existed, and the omission was invisible: the session simply recorded no clock samples
                // and nothing said the corpus could not be joined.
                add(clockGate(inputs))
            }
            add(storage(inputs))
            // Asked for both modes: a session nobody can watch while it runs is a session whose
            // failures are all discovered afterwards, and that is true of an illuminator run too.
            add(telemetry(inputs))
            add(backlog(inputs))
            // Last, and asked for every intent. A shimmed build is not a walk problem or an
            // illuminator problem, it is a "this binary should not be in a building" problem.
            add(buildDiagnostics(inputs))
        }
        return PreflightReport(
            checks = checks,
            atWallMillis = inputs.atWallMillis,
            intent = inputs.intent,
        )
    }

    private fun permissions(inputs: PreflightInputs): PreflightCheck {
        if (inputs.permissions.isEmpty()) {
            return PreflightCheck(
                PreflightCheckId.PERMISSIONS,
                PreflightSeverity.WARN,
                "not checked — no permission statuses were supplied",
                "open the session status screen once, which is where the participant checklist lives",
            )
        }
        val missing = inputs.permissions.filter { it.needsAttention }
        val optional = inputs.permissions.filter { !it.granted && !it.permission.required }
        return when {
            missing.isNotEmpty() -> PreflightCheck(
                PreflightCheckId.PERMISSIONS,
                PreflightSeverity.FAIL,
                missing.joinToString("; ") { "${it.permission.title}: ${it.action}" },
                missing.first().permission.ifMissing,
            )

            optional.isNotEmpty() -> PreflightCheck(
                PreflightCheckId.PERMISSIONS,
                PreflightSeverity.WARN,
                optional.joinToString("; ") { "${it.permission.title} not granted (optional)" },
            )

            else -> PreflightCheck(
                PreflightCheckId.PERMISSIONS,
                PreflightSeverity.PASS,
                "${inputs.permissions.size} granted, including Always location and Bluetooth",
            )
        }
    }

    private fun residency(inputs: PreflightInputs): PreflightCheck {
        if (inputs.residency.isEmpty()) {
            return PreflightCheck(
                PreflightCheckId.RESIDENCY,
                PreflightSeverity.WARN,
                "no residency diagnostics available on this platform build",
            )
        }
        val blockers = inputs.residency.filterNot { it.satisfied }
        return if (blockers.isEmpty()) {
            PreflightCheck(
                PreflightCheckId.RESIDENCY,
                PreflightSeverity.PASS,
                "${inputs.residency.size} checks satisfied",
            )
        } else {
            PreflightCheck(
                PreflightCheckId.RESIDENCY,
                PreflightSeverity.FAIL,
                blockers.joinToString("; ") { "${it.name}: ${it.detail}" },
                "the session stops recording the moment the phone is pocketed — which is the session",
            )
        }
    }

    private fun config(inputs: PreflightInputs): PreflightCheck {
        val config = inputs.config
        if (inputs.intent == SessionIntent.WALK) {
            // A walk needs one thing from the bundle: an advertise namespace, so the frame the fleet
            // scans for has a deployment prefix to match on. Access points, traffic profiles and the
            // collector are all illuminator machinery and are not asked about here.
            return if (config.advertise.isConfigured) {
                PreflightCheck(
                    PreflightCheckId.CONFIG,
                    PreflightSeverity.PASS,
                    "bundle v${config.version}, site ${config.site.ifBlank { "unset" }}",
                )
            } else {
                PreflightCheck(
                    PreflightCheckId.CONFIG,
                    PreflightSeverity.WARN,
                    "bundle v${config.version} has no advertise namespace",
                    "set one by hand from the broadcast panel, or refetch the bundle",
                )
            }
        }
        if (!config.isIlluminationReady) {
            return PreflightCheck(
                PreflightCheckId.CONFIG,
                PreflightSeverity.FAIL,
                "bundle v${config.version} cannot run an illuminator session " +
                    "(collector ${config.collector.host.ifBlank { "unset" }}:" +
                    "${config.collector.udpPort}, ${config.accessPoints.size} AP(s))",
                "refetch the bundle, or set the collector by hand from the panel above",
            )
        }
        if (config.trafficProfiles.none { it.isValid }) {
            return PreflightCheck(
                PreflightCheckId.CONFIG,
                PreflightSeverity.FAIL,
                "no valid traffic profile — there is nothing to illuminate with",
                "refetch the bundle",
            )
        }
        if (!config.beacons.isConfigured) {
            return PreflightCheck(
                PreflightCheckId.CONFIG,
                PreflightSeverity.WARN,
                "no beacon plan: this phone will illuminate but will witness no zones",
            )
        }
        return PreflightCheck(
            PreflightCheckId.CONFIG,
            PreflightSeverity.PASS,
            "bundle v${config.version}, ${config.accessPoints.size} AP(s), " +
                "${config.beacons.zones.size} anchor(s), ${config.trafficProfiles.size} profile(s)",
        )
    }

    private fun collector(inputs: PreflightInputs): PreflightCheck {
        val probe = inputs.collector
        if (!probe.attempted) {
            return PreflightCheck(
                PreflightCheckId.COLLECTOR,
                PreflightSeverity.WARN,
                probe.error ?: "not probed",
            )
        }
        if (!probe.reachable) {
            return PreflightCheck(
                PreflightCheckId.COLLECTOR,
                PreflightSeverity.FAIL,
                probe.error ?: "no reply from the collector",
                "check the AP association and that the socket pinned to Wi-Fi, not cellular",
            )
        }
        val bestRtt = probe.estimates.minOfOrNull { it.delayMillis } ?: 0.0
        return if (bestRtt > RTT_WARN_MILLIS) {
            PreflightCheck(
                PreflightCheckId.COLLECTOR,
                PreflightSeverity.WARN,
                "reachable, but best round-trip is ${bestRtt.roundTo(1)} ms",
                "usually an AP under load, or a socket that is not on the experiment network",
            )
        } else {
            PreflightCheck(
                PreflightCheckId.COLLECTOR,
                PreflightSeverity.PASS,
                "${probe.estimates.size} burst(s), best round-trip ${bestRtt.roundTo(1)} ms",
            )
        }
    }

    /**
     * The check the whole pre-flight exists for: *this phone will fail the clock gate*.
     *
     * What the probe can and cannot prove is stated in the detail line, because the difference
     * matters. Over a few seconds of probing, sample availability and gross offset error are
     * genuinely measured; long-term skew is not, and claiming otherwise would be the kind of green
     * light that costs a session.
     */
    private fun clockGate(inputs: PreflightInputs): PreflightCheck {
        val probe = when (inputs.intent) {
            SessionIntent.WALK -> inputs.referenceClock
            SessionIntent.ILLUMINATE -> inputs.collector
        }
        if (!probe.attempted) {
            return PreflightCheck(
                PreflightCheckId.CLOCK_GATE,
                PreflightSeverity.WARN,
                "not probed — G4 cannot be predicted",
            )
        }
        val report = ClockGate.evaluate(probe.estimates)
        val via = probe.source.ifBlank { "unknown path" }
        val span = "over a ${(probe.spanMillis / 1000.0).roundTo(1)} s probe via $via"
        return when {
            report.status == ClockGateStatus.NO_SAMPLES -> PreflightCheck(
                PreflightCheckId.CLOCK_GATE,
                PreflightSeverity.FAIL,
                "no clock exchange succeeded over $via — nothing this session records can be placed " +
                    "on the fleet timeline",
                if (inputs.intent == SessionIntent.WALK) {
                    "the phone has no route to the backend, or is not signed in — the trajectory and " +
                        "the mesh would be a map of nowhen"
                } else {
                    "the socket reaches the collector for data but not for time, or the collector is " +
                        "not answering TIME_REQUEST"
                },
            )

            report.status == ClockGateStatus.OFFSET_ONLY -> PreflightCheck(
                PreflightCheckId.CLOCK_GATE,
                PreflightSeverity.FAIL,
                "only one usable sample — the affine fit is not identifiable and the fold is flagged",
                "this phone will fail the clock gate as configured",
            )

            !report.meetsAllTestsBudget -> PreflightCheck(
                PreflightCheckId.CLOCK_GATE,
                PreflightSeverity.FAIL,
                "G4a would fail: ${report.precisionLine} $span",
                "this phone will fail the clock gate — its data cannot be placed on the fleet timeline",
            )

            !report.meetsT3Budget -> PreflightCheck(
                PreflightCheckId.CLOCK_GATE,
                PreflightSeverity.WARN,
                "G4a passes but G4b would not: ${report.precisionLine} $span",
                "T3 would drop this fold, and block boundaries on 30 s cycling ramps are exactly " +
                    "what needs the 250 ms budget",
            )

            else -> PreflightCheck(
                PreflightCheckId.CLOCK_GATE,
                PreflightSeverity.PASS,
                "${report.sampleCount} samples, ${report.precisionLine} $span — " +
                    "G4a and G4b both satisfied here. A short probe proves samples and gross " +
                    "offset; it does not prove long-term skew.",
            )
        }
    }

    /**
     * Can this phone put an identity frame on air that the fleet can read?
     *
     * The interesting failure is not "Bluetooth is off" — that is loud. It is iOS backgrounding: the
     * service UUID moves into Apple's proprietary overflow area, which a raw HCI scanner cannot
     * parse, so a backgrounded iPhone advertises something no node in the room can hear. The session
     * looks perfect from the phone. This is where that gets said out loud, before the walk.
     */
    private fun advertise(inputs: PreflightInputs): PreflightCheck {
        val probe = inputs.broadcast
        if (!probe.configured) {
            return PreflightCheck(
                PreflightCheckId.ADVERTISE,
                PreflightSeverity.FAIL,
                "no advertise namespace — there is no identity frame to put on air",
                "the fleet's scanner matches on the namespace prefix; without one nothing it hears " +
                    "can be attributed to this handset",
            )
        }
        if (probe.available == false) {
            return PreflightCheck(
                PreflightCheckId.ADVERTISE,
                PreflightSeverity.FAIL,
                probe.detail ?: "the platform will not advertise",
                "check Bluetooth is on and the advertise permission is granted",
            )
        }
        return if (probe.foregroundOnly) {
            PreflightCheck(
                PreflightCheckId.ADVERTISE,
                PreflightSeverity.WARN,
                "will advertise, but foreground-only on this platform",
                "keep the app on screen for the whole walk — backgrounded, the frame moves into an " +
                    "overflow area the fleet's scanner cannot read, and the phone cannot tell",
            )
        } else {
            PreflightCheck(
                PreflightCheckId.ADVERTISE,
                PreflightSeverity.PASS,
                probe.detail ?: "advertising available",
            )
        }
    }

    /**
     * Will there be a trajectory?
     *
     * A warning and not a blocker. A walk with no track still carries its scanned waypoints, which is
     * discrete rather than continuous ground truth — weaker, not worthless — and an operator who
     * knows that is making a choice rather than losing data.
     */
    private fun tracker(inputs: PreflightInputs): PreflightCheck {
        val probe = inputs.tracker
        if (!probe.requested) {
            return PreflightCheck(
                PreflightCheckId.POSE_TRACKER,
                PreflightSeverity.WARN,
                "tracking is switched off — this walk records waypoints but no trajectory",
                "the fingerprint will have the points you scanned and no line between them",
            )
        }
        if (!probe.available) {
            return PreflightCheck(
                PreflightCheckId.POSE_TRACKER,
                PreflightSeverity.WARN,
                probe.detail ?: "no pose tracking on this device",
                "scan a waypoint at every point you want in the fingerprint — there will be no " +
                    "trajectory to interpolate between them",
            )
        }
        return PreflightCheck(
            PreflightCheckId.POSE_TRACKER,
            PreflightSeverity.PASS,
            probe.detail ?: "pose tracking available",
        )
    }

    /**
     * Will there be a room?
     *
     * THE FAILURE THIS EXISTS TO NAME. On 2026-08-26 a 21-minute walk on an iPhone 17 Pro recorded
     * 4 874 mesh-observation rows and shipped no geometry — the 102.94 MB `mesh.ply` was lost in the
     * upload — and every tool downstream read the absence as "this device has no LiDAR". The two
     * facts have to be separable *before* a walk, and the phone is the only thing that knows which
     * it is.
     *
     * A WARN and never a FAIL. A walk with no mesh still carries its trajectory, its waypoints and
     * its identity frame, and a floor whose geometry is already surveyed does not need a second scan.
     * What is not acceptable is not knowing.
     */
    private fun roomScan(inputs: PreflightInputs): PreflightCheck {
        val probe = inputs.tracker
        if (!probe.requested) {
            return PreflightCheck(
                PreflightCheckId.ROOM_SCAN,
                PreflightSeverity.WARN,
                "tracking is off, so no geometry will be exported either",
            )
        }
        return when (probe.meshAvailable) {
            null -> PreflightCheck(
                PreflightCheckId.ROOM_SCAN,
                PreflightSeverity.WARN,
                "this platform build cannot say whether it exports room geometry",
                "a missing mesh afterwards will be indistinguishable from a device without LiDAR",
            )

            false -> PreflightCheck(
                PreflightCheckId.ROOM_SCAN,
                PreflightSeverity.WARN,
                "no LiDAR on this device — the walk will carry no room geometry",
                "tracking is camera and IMU only, so scale drifts further and there is no mesh to " +
                    "register against the floor plan",
            )

            true -> PreflightCheck(
                PreflightCheckId.ROOM_SCAN,
                PreflightSeverity.PASS,
                "LiDAR present — geometry will be exported. If the console reports no triangles " +
                    "during the walk, the scan is not running and that is fixable in the room.",
            )
        }
    }

    /**
     * Can anybody watch this session while it runs?
     *
     * On 2026-08-26 the answer was no and nothing said so: the handset shipped zero lines for a
     * 21-minute walk because the bundle carried no telemetry endpoint, and a silent courier looked
     * exactly like a working one. The walk was therefore unobservable until it uploaded — and that
     * upload lost its largest artefact.
     *
     * A WARN. The measurement does not depend on it: every sample is on disk and every artefact
     * still uploads. What is lost is the ability to notice a degraded walk while somebody is still
     * standing in the room, which is worth one line before the walk starts.
     */
    private fun telemetry(inputs: PreflightInputs): PreflightCheck {
        val posture = inputs.telemetry
        return when {
            !posture.configured -> PreflightCheck(
                PreflightCheckId.TELEMETRY,
                PreflightSeverity.WARN,
                "no telemetry endpoint in the bundle — nothing about this session will be visible " +
                    "until it uploads",
                "press Reload config; if the endpoint is still absent, this deployment has no " +
                    "public collector and the walk is observable only on this screen",
            )

            posture.lastError != null && posture.flushes == 0 -> PreflightCheck(
                PreflightCheckId.TELEMETRY,
                PreflightSeverity.WARN,
                "configured for ${posture.endpoint} but nothing has shipped: ${posture.lastError}",
                "usually a credential the collector rejects, or no route out from this network",
            )

            else -> PreflightCheck(
                PreflightCheckId.TELEMETRY,
                PreflightSeverity.PASS,
                "shipping to ${posture.endpoint}" +
                    if (posture.flushes > 0) " (${posture.samplesShipped} sample(s) so far)" else "",
            )
        }
    }

    private fun storage(inputs: PreflightInputs): PreflightCheck {
        val required = estimateSessionBytes(inputs.commandedRateHz, inputs.plannedSessionSeconds)
        val available = inputs.storage.availableBytes
        val requiredMb = required / (1024 * 1024)
        val availableMb = available / (1024 * 1024)
        return when {
            available <= 0L -> PreflightCheck(
                PreflightCheckId.STORAGE,
                PreflightSeverity.WARN,
                "free space unknown on this platform build",
            )

            available < required -> PreflightCheck(
                PreflightCheckId.STORAGE,
                PreflightSeverity.FAIL,
                "$availableMb MB free, this session needs about $requiredMb MB",
                "SQLite starts failing inserts when the disk fills and the streams stop without " +
                    "an error the app can show",
            )

            available < required * 2 -> PreflightCheck(
                PreflightCheckId.STORAGE,
                PreflightSeverity.WARN,
                "$availableMb MB free against about $requiredMb MB needed — no margin for a second " +
                    "session before uploading",
            )

            else -> PreflightCheck(
                PreflightCheckId.STORAGE,
                PreflightSeverity.PASS,
                "$availableMb MB free, about $requiredMb MB needed",
            )
        }
    }

    private fun backlog(inputs: PreflightInputs): PreflightCheck {
        if (inputs.backlogSessions == 0L && inputs.backlogScans == 0L) {
            return PreflightCheck(
                PreflightCheckId.BACKLOG,
                PreflightSeverity.PASS,
                "nothing pending from earlier sessions",
            )
        }
        return PreflightCheck(
            PreflightCheckId.BACKLOG,
            PreflightSeverity.WARN,
            "${inputs.backlogSessions} session(s) and ${inputs.backlogScans} scan(s) still unsent",
            "upload before the day starts; the phone will be on an experiment AP with no route out",
        )
    }

    /**
     * A FAIL, not a WARN, and the severity is the measured consequence rather than a preference.
     *
     * With the shims on, one Compose frame can hold the main thread past the five seconds iOS gives
     * an app to quiesce on a background transition. The kill is `SIGKILL` from FrontBoard, so no
     * `stop()` runs, no sidecar is written, and the session stays `open` — which is the one state no
     * upload path selects. The 2026-09-04 walk lost 882 s of pose, mesh and marker data that way.
     *
     * It stays a check rather than a hard refusal, in keeping with every other criterion here: a
     * deliberate instrumented run at a bench is legitimate. What is not legitimate is not knowing.
     */
    private fun buildDiagnostics(inputs: PreflightInputs): PreflightCheck {
        val active = inputs.buildDiagnostics.active
        if (active.isEmpty()) {
            return PreflightCheck(
                PreflightCheckId.BUILD_DIAGNOSTICS,
                PreflightSeverity.PASS,
                "no debug instrumentation in this process",
            )
        }
        return PreflightCheck(
            PreflightCheckId.BUILD_DIAGNOSTICS,
            PreflightSeverity.FAIL,
            active.joinToString(", ") + " active",
            "these intercept every Metal draw call and contend the ObjC side-table lock on the main "
                + "thread; iOS kills the app with 0x8BADF00D on the next background transition and an "
                + "open session is never uploaded. Run a Release build, or turn them off in the Xcode "
                + "scheme under Run > Diagnostics and Run > Options > GPU Frame Capture",
        )
    }
}

/** Everything the evaluation reads. Gathered by `PreflightService`, never by [Preflight] itself. */
data class PreflightInputs(
    /** Which mode of session is about to run. Decides which checks are asked at all. */
    val intent: SessionIntent = SessionIntent.ILLUMINATE,
    val residency: List<ResidencyCheck> = emptyList(),
    val permissions: List<PermissionStatus> = emptyList(),
    val config: LabConfig = LabConfig.EMPTY,
    val collector: CollectorProbe = CollectorProbe.NOT_ATTEMPTED,
    /**
     * The clock path a session with no collector uses. See `ReferenceClock`.
     *
     * A separate field rather than a reused one so the report can say which transport it judged, and so
     * an illuminator session cannot accidentally be graded on an HTTP probe's precision.
     */
    val referenceClock: ClockProbe = ClockProbe.NOT_ATTEMPTED,
    val broadcast: BroadcastProbe = BroadcastProbe.UNKNOWN,
    val tracker: TrackerProbe = TrackerProbe.NOT_REQUESTED,
    /**
     * What the handset's own telemetry courier is doing.
     *
     * Read from the shipper rather than probed, because the shipper is the only thing that knows —
     * and its silence when unconfigured is precisely what made a whole walk invisible.
     */
    val telemetry: TelemetryPosture = TelemetryPosture.UNKNOWN,
    val storage: StorageProbe = StorageProbe.UNKNOWN,
    val backlogSessions: Long = 0,
    val backlogScans: Long = 0,
    /**
     * Xcode's interception shims, read from this process rather than from the build config.
     *
     * The build config is not the authority: the scheme can be edited, an old build can be left
     * on a device, and `isDebug()` is true for plenty of runs that carry none of these. The
     * process's own environment is the only thing that answers for the binary actually running.
     */
    val buildDiagnostics: BuildDiagnostics = BuildDiagnostics.NONE,
    val commandedRateHz: Double = 0.0,
    val plannedSessionSeconds: Int = Preflight.PLANNED_SESSION_SECONDS,
    val atWallMillis: Long = 0,
)

/**
 * What a short live clock exchange produced, over whichever path the mode uses.
 *
 * Several bursts rather than one, because a single burst yields a single [ClockEstimate] and gate
 * G4 needs two before the affine fit is identifiable at all. Detecting "this phone would be
 * downgraded to offset-only" is one of the two things this probe is for.
 *
 * One type for both transports, deliberately. The four timestamps and the minimum-delay reduction are
 * identical whether they came over the collector's UDP socket or over `GET /api/lab/time`; only the
 * precision differs, and [source] records which so a reader knows which budget applies. Two types
 * would mean two copies of the G4 evaluation, and they would drift.
 */
data class ClockProbe(
    val attempted: Boolean,
    val reachable: Boolean,
    /** Which path produced these samples, e.g. `udp/collector` or `http/api-lab-time`. */
    val source: String = "",
    val estimates: List<ClockEstimate> = emptyList(),
    /** Monotonic span the bursts were spread over. States what the residual is evidence about. */
    val spanMillis: Long = 0,
    val error: String? = null,
) {
    companion object {
        val NOT_ATTEMPTED = ClockProbe(attempted = false, reachable = false)
    }
}

/** The collector path's probe. See [ClockProbe] for why there is only one shape. */
typealias CollectorProbe = ClockProbe

data class StorageProbe(val availableBytes: Long, val totalBytes: Long) {
    companion object {
        val UNKNOWN = StorageProbe(0, 0)
    }
}

/**
 * What the platform says about advertising, before a walk.
 *
 * [available] is nullable because "the platform did not say" is a third answer. A build that cannot
 * interrogate its own advertiser must warn rather than pass, and must not fail — a false blocker
 * teaches the operator to ignore blockers.
 */
data class BroadcastProbe(
    val configured: Boolean,
    val available: Boolean? = null,
    val foregroundOnly: Boolean = false,
    val detail: String? = null,
) {
    companion object {
        val UNKNOWN = BroadcastProbe(configured = false)
    }
}

/**
 * What the platform says about odometry, and whether this walk asked for it.
 *
 * [meshAvailable] is nullable because "the build did not say" is a third answer, and the whole point
 * of carrying it is to separate *that* from a definite no. A missing mesh afterwards is otherwise
 * indistinguishable from a device that never had LiDAR — which is the 2026-08-26 confusion, on a
 * phone that has one.
 */
data class TrackerProbe(
    val requested: Boolean,
    val available: Boolean = false,
    val detail: String? = null,
    val meshAvailable: Boolean? = null,
) {
    companion object {
        val NOT_REQUESTED = TrackerProbe(requested = false)
    }
}

/**
 * What kind of session is about to run.
 *
 * Two modes, because the phone has two genuinely different jobs and they share almost no
 * prerequisites. Naming them is what lets the readiness check be honest instead of uniformly red.
 */
enum class SessionIntent(val label: String) {
    /**
     * A fingerprinting walk: advertise an identity the fleet can hear, and record where the handset
     * was while it did. Needs no access point, so it needs no collector and no UDP clock exchange.
     */
    WALK("walk"),

    /**
     * The illuminator role: associate to an access point, pin a socket, discipline the clock, emit
     * at a commanded rate. Requires infrastructure this deployment does not currently have — the
     * fleet's AX210 cannot run AP mode.
     */
    ILLUMINATE("illuminate"),
    ;

    val wire: String get() = name.lowercase()
}

enum class PreflightSeverity { PASS, WARN, FAIL }

enum class PreflightCheckId(val wire: String, val title: String) {
    PERMISSIONS("permissions", "Permissions"),
    RESIDENCY("residency", "Background residency"),
    CONFIG("config", "Config bundle"),
    COLLECTOR("collector", "Collector reachable"),
    CLOCK_GATE("clock_gate", "Clock gate G4"),
    /** Can the fleet hear this handset's identity frame? Asked for a walk. */
    ADVERTISE("advertise", "Identity frame"),
    /** Will there be a trajectory? Asked for a walk. */
    POSE_TRACKER("pose_tracker", "Pose tracking"),

    /** Will there be a room? Asked for a walk. */
    ROOM_SCAN("room_scan", "Room geometry"),

    /** Can anybody watch this session while it runs? */
    TELEMETRY("telemetry", "Live telemetry"),
    STORAGE("storage", "Storage headroom"),
    BACKLOG("backlog", "Upload backlog"),

    /**
     * Is this the build that should be carrying a field session at all?
     *
     * Asked last because it is the only check that is about the binary rather than about the
     * room, and asked at all because on 2026-09-04 it was the difference between a walk and a
     * watchdog kill — see [sk.martinvanco.monad.lab.domain.BuildDiagnostics].
     */
    BUILD_DIAGNOSTICS("build_diagnostics", "Build diagnostics"),
}

data class PreflightCheck(
    val id: PreflightCheckId,
    val severity: PreflightSeverity,
    val detail: String,
    /** What it costs, or what to do about it. Shown only when it is not a PASS. */
    val remedy: String? = null,
)

data class PreflightReport(
    val checks: List<PreflightCheck>,
    val atWallMillis: Long,
    /**
     * Which mode was judged.
     *
     * On the report and not only in the inputs, because a report is read after the fact — and "READY"
     * is meaningless without knowing ready *for what*. A walk that passed says nothing about whether
     * the same phone could illuminate.
     */
    val intent: SessionIntent = SessionIntent.ILLUMINATE,
) {
    val blockers: List<PreflightCheck> get() = checks.filter { it.severity == PreflightSeverity.FAIL }
    val warnings: List<PreflightCheck> get() = checks.filter { it.severity == PreflightSeverity.WARN }

    /** No blockers. Warnings are compatible with go — they are things to know, not to fix. */
    val isGo: Boolean get() = blockers.isEmpty()

    val headline: String
        get() = when {
            blockers.isNotEmpty() ->
                "NOT READY to ${intent.label} — ${blockers.size} blocker(s): " +
                    blockers.joinToString(", ") { it.id.title }

            warnings.isNotEmpty() -> "READY to ${intent.label} with ${warnings.size} warning(s)"
            else -> "READY to ${intent.label} — every check passed"
        }
}
