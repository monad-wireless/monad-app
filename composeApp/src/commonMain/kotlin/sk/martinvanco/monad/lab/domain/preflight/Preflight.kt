package sk.martinvanco.monad.lab.domain.preflight

import sk.martinvanco.monad.core.domain.permissions.PermissionStatus
import sk.martinvanco.monad.lab.domain.ClockEstimate
import sk.martinvanco.monad.lab.domain.ClockGate
import sk.martinvanco.monad.lab.domain.ClockGateStatus
import sk.martinvanco.monad.lab.domain.LabConfig
import sk.martinvanco.monad.lab.domain.ResidencyCheck
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
 * | Backlog | — | anything still unsent from a previous session |
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

    fun evaluate(inputs: PreflightInputs): PreflightReport {
        val checks = listOf(
            permissions(inputs),
            residency(inputs),
            config(inputs),
            collector(inputs),
            clockGate(inputs),
            storage(inputs),
            backlog(inputs),
        )
        return PreflightReport(checks = checks, atWallMillis = inputs.atWallMillis)
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
        val probe = inputs.collector
        if (!probe.attempted) {
            return PreflightCheck(
                PreflightCheckId.CLOCK_GATE,
                PreflightSeverity.WARN,
                "not probed — G4 cannot be predicted",
            )
        }
        val report = ClockGate.evaluate(probe.estimates)
        val span = "over a ${(probe.spanMillis / 1000.0).roundTo(1)} s probe"
        return when {
            report.status == ClockGateStatus.NO_SAMPLES -> PreflightCheck(
                PreflightCheckId.CLOCK_GATE,
                PreflightSeverity.FAIL,
                "no clock exchange succeeded — every fold from this phone would be excluded",
                "the socket reaches the collector for data but not for time, or the collector is " +
                    "not answering TIME_REQUEST",
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
}

/** Everything the evaluation reads. Gathered by `PreflightService`, never by [Preflight] itself. */
data class PreflightInputs(
    val residency: List<ResidencyCheck> = emptyList(),
    val permissions: List<PermissionStatus> = emptyList(),
    val config: LabConfig = LabConfig.EMPTY,
    val collector: CollectorProbe = CollectorProbe.NOT_ATTEMPTED,
    val storage: StorageProbe = StorageProbe.UNKNOWN,
    val backlogSessions: Long = 0,
    val backlogScans: Long = 0,
    val commandedRateHz: Double = 0.0,
    val plannedSessionSeconds: Int = Preflight.PLANNED_SESSION_SECONDS,
    val atWallMillis: Long = 0,
)

/**
 * What a short live exchange with the collector produced.
 *
 * Several bursts rather than one, because a single burst yields a single [ClockEstimate] and gate
 * G4 needs two before the affine fit is identifiable at all. Detecting "this phone would be
 * downgraded to offset-only" is one of the two things this probe is for.
 */
data class CollectorProbe(
    val attempted: Boolean,
    val reachable: Boolean,
    val estimates: List<ClockEstimate> = emptyList(),
    /** Monotonic span the bursts were spread over. States what the residual is evidence about. */
    val spanMillis: Long = 0,
    val error: String? = null,
) {
    companion object {
        val NOT_ATTEMPTED = CollectorProbe(attempted = false, reachable = false)
    }
}

data class StorageProbe(val availableBytes: Long, val totalBytes: Long) {
    companion object {
        val UNKNOWN = StorageProbe(0, 0)
    }
}

enum class PreflightSeverity { PASS, WARN, FAIL }

enum class PreflightCheckId(val wire: String, val title: String) {
    PERMISSIONS("permissions", "Permissions"),
    RESIDENCY("residency", "Background residency"),
    CONFIG("config", "Config bundle"),
    COLLECTOR("collector", "Collector reachable"),
    CLOCK_GATE("clock_gate", "Clock gate G4"),
    STORAGE("storage", "Storage headroom"),
    BACKLOG("backlog", "Upload backlog"),
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
) {
    val blockers: List<PreflightCheck> get() = checks.filter { it.severity == PreflightSeverity.FAIL }
    val warnings: List<PreflightCheck> get() = checks.filter { it.severity == PreflightSeverity.WARN }

    /** No blockers. Warnings are compatible with go — they are things to know, not to fix. */
    val isGo: Boolean get() = blockers.isEmpty()

    val headline: String
        get() = when {
            blockers.isNotEmpty() ->
                "NOT READY — ${blockers.size} blocker(s): " +
                    blockers.joinToString(", ") { it.id.title }

            warnings.isNotEmpty() -> "READY with ${warnings.size} warning(s)"
            else -> "READY — every check passed"
        }
}
