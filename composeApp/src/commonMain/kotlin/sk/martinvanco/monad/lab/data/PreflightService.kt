package sk.martinvanco.monad.lab.data

import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import sk.martinvanco.monad.core.domain.permissions.PermissionStatus
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.lab.domain.BackgroundResidency
import sk.martinvanco.monad.lab.domain.ClockSyncService
import sk.martinvanco.monad.lab.domain.LabConfig
import sk.martinvanco.monad.lab.domain.LabDatagramSocket
import sk.martinvanco.monad.lab.domain.IdentityBroadcaster
import sk.martinvanco.monad.lab.domain.LabEnvironment
import sk.martinvanco.monad.lab.domain.LabSensorModule
import sk.martinvanco.monad.lab.domain.PoseTracker
import sk.martinvanco.monad.lab.domain.ReferenceClock
import sk.martinvanco.monad.lab.domain.availableStorageBytes
import sk.martinvanco.monad.lab.domain.detectBuildDiagnostics
import sk.martinvanco.monad.lab.domain.monotonicNanos
import sk.martinvanco.monad.lab.domain.preflight.BroadcastProbe
import sk.martinvanco.monad.lab.domain.preflight.ClockProbe
import sk.martinvanco.monad.lab.domain.preflight.CollectorProbe
import sk.martinvanco.monad.lab.domain.preflight.SessionIntent
import sk.martinvanco.monad.lab.domain.preflight.TrackerProbe
import sk.martinvanco.monad.lab.domain.preflight.Preflight
import sk.martinvanco.monad.lab.domain.preflight.PreflightInputs
import sk.martinvanco.monad.lab.domain.preflight.PreflightReport
import sk.martinvanco.monad.lab.domain.preflight.StorageProbe
import sk.martinvanco.monad.lab.domain.totalStorageBytes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Gathers what the pre-flight needs, then hands it to [Preflight] to judge.
 *
 * The split is the point: everything that decides pass/warn/fail is pure and tested; everything
 * here opens sockets and reads file systems and is therefore untestable without a device.
 *
 * Two properties are load-bearing:
 *
 * - **It refuses to run while a session is live.** The probe opens the same singleton socket the
 *   instrument uses, so running it mid-session would steal the illuminator's socket. The caller
 *   passes [sessionRunning] and gets a "not probed" result rather than a broken session.
 * - **It resets the clock service afterwards, always.** `ClockSyncService` is a singleton whose
 *   history persists, and gate G4 fits per `recording_session_id`. A probe's samples left in that
 *   history would contaminate the next session's fit with samples from before it started — the
 *   exact class of bug the `reset()` at session start was added to kill. This one is its mirror.
 */
@OptIn(ExperimentalUuidApi::class)
class PreflightService(
    private val socket: LabDatagramSocket,
    private val clockSync: ClockSyncService,
    private val environment: LabEnvironment,
    private val residency: BackgroundResidency,
    private val sessions: LabSessionRepository,
    private val groundTruth: GroundTruthRepository,
    private val broadcaster: IdentityBroadcaster,
    private val poseTracker: PoseTracker,
    private val referenceClock: ReferenceClock,
    /**
     * The telemetry courier, read for its posture.
     *
     * Not probed: the shipper is the only thing that knows whether the bundle named an endpoint,
     * and its by-design silence when it did not is what made a whole walk invisible on 2026-08-26.
     */
    private val telemetry: LabTelemetryShipper,
) {

    /**
     * Judge readiness for one mode of session.
     *
     * [intent] decides which probes even run, and the saving is not cosmetic: the collector probe
     * opens the shared datagram socket and spends six seconds on it. A walk has no collector to probe
     * — the fleet's AX210 cannot run an access point — so asking would spend the socket and the wait
     * to produce a failure that was certain in advance.
     */
    suspend fun run(
        config: LabConfig,
        commandedRateHz: Double,
        permissions: List<PermissionStatus> = emptyList(),
        sessionRunning: Boolean = false,
        intent: SessionIntent = SessionIntent.ILLUMINATE,
        trackRequested: Boolean = false,
    ): PreflightReport {
        val probe = when {
            intent != SessionIntent.ILLUMINATE -> CollectorProbe.NOT_ATTEMPTED
            sessionRunning -> CollectorProbe(
                attempted = false,
                reachable = false,
                error = "a session is running — the probe would take the illuminator's socket",
            )

            else -> probeCollector(config)
        }

        // The walk's clock path. Probed here rather than assumed, because the answer it gives — "nothing
        // this walk records could be placed on the fleet timeline" — is worth having before somebody has
        // walked a building. Costs a handful of small HTTP requests and no socket.
        val reference = if (intent == SessionIntent.WALK) probeReference(config) else ClockProbe.NOT_ATTEMPTED

        val inputs = PreflightInputs(
            intent = intent,
            residency = runCatching { residency.diagnostics() }.getOrDefault(emptyList()),
            permissions = permissions,
            config = config,
            collector = probe,
            referenceClock = reference,
            broadcast = probeBroadcast(config),
            tracker = probeTracker(trackRequested),
            telemetry = telemetry.posture.value,
            storage = StorageProbe(
                availableBytes = runCatching { availableStorageBytes() }.getOrDefault(0L),
                totalBytes = runCatching { totalStorageBytes() }.getOrDefault(0L),
            ),
            backlogSessions = runCatching { sessions.unsyncedCount() }.getOrDefault(0L),
            backlogScans = runCatching { groundTruth.pendingCount() }.getOrDefault(0L),
            commandedRateHz = commandedRateHz,
            // Read here rather than cached at startup: it costs one environment lookup and a
            // cached answer would survive nothing that matters, since the process cannot gain or
            // lose a shim while it runs.
            buildDiagnostics = detectBuildDiagnostics(),
            atWallMillis = currentTimeMillis(),
        )
        return Preflight.evaluate(inputs)
    }

    /**
     * A short burst against the reference clock, for a walk.
     *
     * [PROBE_BURSTS] bursts spaced apart for the same reason the collector probe uses several: one burst
     * is one sample, and gate G4 needs two before the affine fit exists at all — so a single-burst probe
     * could not tell "this walk syncs" from "this walk will be downgraded to offset-only".
     *
     * Smaller bursts than the UDP path. Each exchange here is a whole HTTP request rather than a
     * datagram, so eight of them per burst would make pressing *Check* a visible wait for precision the
     * transport cannot deliver anyway.
     *
     * Resets the clock service afterwards, always, for the same reason [probeCollector] does: G4 fits per
     * recording session, and a probe's samples left in the history would contaminate the next session's
     * fit with points from before it started.
     */
    private suspend fun probeReference(config: LabConfig): ClockProbe {
        clockSync.reset()
        val policy = config.clockSync.copy(
            burstSize = REFERENCE_PROBE_BURST_SIZE,
            burstSpacingMs = REFERENCE_PROBE_BURST_SPACING_MILLIS,
        )
        val startedMono = monotonicNanos()
        var lastError: String? = null
        try {
            repeat(PROBE_BURSTS) { index ->
                if (index > 0) delay(PROBE_GAP_MILLIS)
                clockSync.runReferenceBurst(referenceClock, policy)
                    .onFailure { lastError = it.message }
            }
            val estimates = clockSync.history.value
            return ClockProbe(
                attempted = true,
                reachable = estimates.isNotEmpty(),
                source = referenceClock.source,
                estimates = estimates,
                spanMillis = (monotonicNanos() - startedMono) / 1_000_000L,
                error = if (estimates.isEmpty()) lastError ?: "no reply from the reference clock" else null,
            )
        } finally {
            clockSync.reset()
            Napier.i("[lab] pre-flight reference clock probe finished")
        }
    }

    /**
     * What the platform will accept for advertising, without putting anything on air.
     *
     * Read from the broadcaster's own diagnostics rather than by starting an advertisement: a probe
     * that briefly went on air would appear in the fleet's scan as a phantom sighting with no session
     * behind it, and a stray identity frame in the corpus is worse than an unprobed check.
     *
     * `foregroundOnly` is inferred from the platform's stated posture, which is why the diagnostics
     * strings are searched rather than a flag being read: [IdentityBroadcaster] reports its posture as
     * prose because the posture differs per platform in ways a boolean cannot carry.
     */
    private fun probeBroadcast(config: LabConfig): BroadcastProbe {
        val diagnostics = runCatching { broadcaster.diagnostics() }.getOrDefault(emptyList())
        if (diagnostics.isEmpty()) {
            return BroadcastProbe(
                configured = config.advertise.isConfigured,
                available = null,
                detail = "this platform build reports no advertiser diagnostics",
            )
        }
        val joined = diagnostics.joinToString("; ")
        val blocked = diagnostics.any { line ->
            val lower = line.lowercase()
            lower.contains("missing") || lower.contains("not supported") ||
                lower.contains("unsupported") || lower.contains("powered off") ||
                lower.contains("unauthorized")
        }
        return BroadcastProbe(
            configured = config.advertise.isConfigured,
            available = !blocked,
            foregroundOnly = diagnostics.any { it.lowercase().contains("foreground") },
            detail = joined,
        )
    }

    /**
     * Runtime odometry availability, and whether this device exports room geometry.
     *
     * Never starts a session — [PoseTracker.probe] does not, and the mesh answer comes off the
     * tracker's own diagnostics rather than from a scan.
     *
     * The mesh half is read from a **diagnostics line** rather than from a flag, for the same reason
     * the advertiser's posture is: the platforms disagree about what "has depth" means in ways a
     * boolean cannot carry, and the tracker already prints the one sentence that settles it. Null
     * when the build prints nothing, which is a third answer and not a no — a missing mesh afterwards
     * is otherwise indistinguishable from a device that never had LiDAR.
     */
    private suspend fun probeTracker(requested: Boolean): TrackerProbe {
        if (!requested) return TrackerProbe.NOT_REQUESTED
        val mesh = meshAvailability()
        return when (val availability = runCatching { poseTracker.probe() }.getOrNull()) {
            is LabSensorModule.Availability.Available -> TrackerProbe(
                requested = true,
                available = true,
                detail = "pose tracking available",
                meshAvailable = mesh,
            )

            is LabSensorModule.Availability.NeedsPermission -> TrackerProbe(
                requested = true,
                available = false,
                detail = "pose tracking needs the ${availability.permission} permission",
                meshAvailable = mesh,
            )

            is LabSensorModule.Availability.Unsupported -> TrackerProbe(
                requested = true,
                available = false,
                detail = availability.reason,
                meshAvailable = mesh,
            )

            null -> TrackerProbe(
                requested = true,
                available = false,
                detail = "the tracker could not be probed on this build",
                meshAvailable = mesh,
            )
        }
    }

    /**
     * Whether this device says it reconstructs room geometry. Null when it says nothing.
     *
     * Matches on the diagnostics key the tracker prints (`LiDAR scene reconstruction: …`). A rename
     * there turns a definite answer into a null, which is the safe direction: the check then warns
     * that the platform did not say, rather than claiming a device has no LiDAR.
     */
    private fun meshAvailability(): Boolean? {
        val line = runCatching { poseTracker.diagnostics() }
            .getOrDefault(emptyList())
            .firstOrNull { it.startsWith(MESH_DIAGNOSTIC_KEY) }
            ?: return null
        return when {
            line.contains("available") -> true
            line.contains("absent") -> false
            else -> null
        }
    }

    /**
     * Open the socket, exchange time a few times, and close it again.
     *
     * [PROBE_BURSTS] bursts spaced [PROBE_GAP_MILLIS] apart, because **one burst is one sample** and
     * gate G4 needs two before the affine fit exists at all — a probe that fired once could not
     * distinguish "this phone syncs" from "this phone will be downgraded to offset-only", which is
     * half of what the check is for. Three gives a residual as well.
     *
     * What the residual over a few seconds proves is narrow and is labelled as such by the
     * evaluation: sample availability and gross offset error, not long-term skew.
     */
    private suspend fun probeCollector(config: LabConfig): CollectorProbe {
        if (!config.isIlluminationReady) {
            return CollectorProbe(
                attempted = false,
                reachable = false,
                error = "the config bundle has no usable collector to probe",
            )
        }
        val opened = socket.open(
            host = config.collector.host,
            port = config.collector.udpPort,
            interfaceHint = environment.wifiInterfaceHint,
        )
        if (opened.isFailure) {
            return CollectorProbe(
                attempted = true,
                reachable = false,
                error = "socket open failed: ${opened.exceptionOrNull()?.message}",
            )
        }

        // The probe must not inherit, or leave behind, a session's samples.
        clockSync.reset()
        val sessionBytes = Uuid.random().toByteArray()
        val policy = config.clockSync.copy(
            burstSize = PROBE_BURST_SIZE,
            burstSpacingMs = PROBE_BURST_SPACING_MILLIS,
            timeoutMs = PROBE_EXCHANGE_TIMEOUT_MILLIS,
        )
        val startedMono = monotonicNanos()
        var lastError: String? = null
        try {
            repeat(PROBE_BURSTS) { index ->
                if (index > 0) delay(PROBE_GAP_MILLIS)
                clockSync.runBurst(sessionBytes, policy)
                    .onFailure { lastError = it.message }
            }
            val estimates = clockSync.history.value
            val spanMillis = (monotonicNanos() - startedMono) / 1_000_000L
            return CollectorProbe(
                attempted = true,
                reachable = estimates.isNotEmpty(),
                source = "udp/collector",
                estimates = estimates,
                spanMillis = spanMillis,
                error = if (estimates.isEmpty()) {
                    lastError ?: "no reply to any time request"
                } else {
                    null
                },
            )
        } finally {
            socket.close()
            // Mirror of the session-start reset: a probe's samples must never reach a session's
            // clock.tsv, because G4 fits per recording session and would be fitting foreign points.
            clockSync.reset()
            Napier.i("[lab] pre-flight collector probe finished")
        }
    }

    private companion object {
        /** The tracker's own key for the depth answer. Mirrored, not duplicated — see [meshAvailability]. */
        const val MESH_DIAGNOSTIC_KEY = "LiDAR scene reconstruction:"

        /** Enough to exceed G4's two-sample threshold and leave one over for a residual. */
        const val PROBE_BURSTS = 3

        const val PROBE_BURST_SIZE = 8
        const val PROBE_BURST_SPACING_MILLIS = 20L
        const val PROBE_EXCHANGE_TIMEOUT_MILLIS = 500L

        /**
         * Separation between probe bursts.
         *
         * Two seconds: long enough that the three anchors are distinguishable so the affine fit is
         * identifiable at all, short enough that the whole pre-flight is a button press rather than
         * a wait. It is deliberately *not* long enough to measure skew, and the evaluation says so.
         */
        const val PROBE_GAP_MILLIS = 2_000L

        /**
         * Exchanges per reference-clock burst.
         *
         * Four, against the UDP path's eight. Each one is a full HTTP request, so the minimum-delay
         * filter gets a real choice without turning a readiness check into a wait.
         */
        const val REFERENCE_PROBE_BURST_SIZE = 4

        /** No artificial gap inside an HTTP burst — the requests are already hundreds of times slower. */
        const val REFERENCE_PROBE_BURST_SPACING_MILLIS = 0L
    }
}
