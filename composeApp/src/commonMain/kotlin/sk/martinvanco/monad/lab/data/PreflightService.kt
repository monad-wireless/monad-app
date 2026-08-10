package sk.martinvanco.monad.lab.data

import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import sk.martinvanco.monad.core.domain.permissions.PermissionStatus
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.lab.domain.BackgroundResidency
import sk.martinvanco.monad.lab.domain.ClockSyncService
import sk.martinvanco.monad.lab.domain.LabConfig
import sk.martinvanco.monad.lab.domain.LabDatagramSocket
import sk.martinvanco.monad.lab.domain.LabEnvironment
import sk.martinvanco.monad.lab.domain.availableStorageBytes
import sk.martinvanco.monad.lab.domain.monotonicNanos
import sk.martinvanco.monad.lab.domain.preflight.CollectorProbe
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
) {

    suspend fun run(
        config: LabConfig,
        commandedRateHz: Double,
        permissions: List<PermissionStatus> = emptyList(),
        sessionRunning: Boolean = false,
    ): PreflightReport {
        val probe = if (sessionRunning) {
            CollectorProbe(
                attempted = false,
                reachable = false,
                error = "a session is running — the probe would take the illuminator's socket",
            )
        } else {
            probeCollector(config)
        }

        val inputs = PreflightInputs(
            residency = runCatching { residency.diagnostics() }.getOrDefault(emptyList()),
            permissions = permissions,
            config = config,
            collector = probe,
            storage = StorageProbe(
                availableBytes = runCatching { availableStorageBytes() }.getOrDefault(0L),
                totalBytes = runCatching { totalStorageBytes() }.getOrDefault(0L),
            ),
            backlogSessions = runCatching { sessions.unsyncedCount() }.getOrDefault(0L),
            backlogScans = runCatching { groundTruth.pendingCount() }.getOrDefault(0L),
            commandedRateHz = commandedRateHz,
            atWallMillis = currentTimeMillis(),
        )
        return Preflight.evaluate(inputs)
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
    }
}
