package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sk.martinvanco.monad.core.util.currentTimeMillis
import kotlin.math.sqrt

/**
 * The illuminator role: emit UDP datagrams at a **commanded** pace and measure the pace actually
 * achieved.
 *
 * Two design decisions carry the whole experiment:
 *
 * 1. **Absolute scheduling, not sleep-per-packet.** The n-th packet is due at `t0 + n/R`. A loop
 *    that does `delay(1/R)` accumulates every scheduler overshoot, so its mean rate sags and its
 *    jitter grows without bound. Scheduling against an absolute origin makes a late packet a local
 *    error rather than a permanent phase shift.
 * 2. **Report delivered, not commanded.** [TrafficStats] carries the realised inter-arrival
 *    statistics. EXP-P2 measured a broadcast illuminator whose *commanded* 100 Hz delivered 30 Hz
 *    at CV 1.6–2.5; a source that does not report its own realised pace cannot be distinguished
 *    from that case, and is therefore not usable as a sampling axis.
 *
 * The coefficient of variation reported here is of the **send** times, on-device. It bounds what
 * the observer can possibly see; the observer's own arrival statistics are the real axis.
 */
class TrafficGenerator(
    private val socket: LabDatagramSocket,
) {

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    private val _stats = MutableStateFlow(TrafficStats.IDLE)
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    private val _isEmitting = MutableStateFlow(false)
    val isEmitting: StateFlow<Boolean> = _isEmitting.asStateFlow()

    /**
     * Start emitting. The socket must already be open and pinned — this class deliberately does
     * not open it, so that a binding failure is surfaced by the caller rather than buried here.
     */
    fun start(
        sessionId: ByteArray,
        profile: TrafficProfile,
        onSample: (TrafficSample) -> Unit = {},
    ): Result<Unit> {
        if (_isEmitting.value) return Result.failure(IllegalStateException("already emitting"))
        if (!profile.isValid) return Result.failure(IllegalArgumentException("invalid profile: $profile"))

        val periodNanos = (1_000_000_000.0 / profile.rateHz).toLong()
        val totalPackets = (profile.rateHz * profile.durationSeconds).toLong().coerceAtLeast(1)
        val payload = ByteArray(profile.payloadBytes) { (it and 0xFF).toByte() }

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        _isEmitting.value = true

        job = newScope.launch {
            val origin = monotonicNanos()
            var sent = 0L
            var failed = 0L
            var lastSendNanos = 0L
            // Streaming moments, so a 30-minute session at 200 Hz never accumulates a list.
            var intervalCount = 0L
            var intervalSum = 0.0
            var intervalSumSq = 0.0
            var maxGapNanos = 0L
            var lateSum = 0.0

            _stats.value = TrafficStats.IDLE.copy(
                commandedRateHz = profile.rateHz,
                targetPackets = totalPackets,
                startedAtNanos = origin,
            )

            for (n in 0 until totalPackets) {
                if (!isActive) break

                val dueAt = origin + n * periodNanos
                var now = monotonicNanos()
                val waitNanos = dueAt - now
                if (waitNanos > 0) {
                    // delay() has millisecond granularity; the residual sub-millisecond wait is
                    // absorbed by the absolute schedule on the next iteration rather than spun on.
                    delay(waitNanos / 1_000_000)
                    now = monotonicNanos()
                }

                val lateness = (now - dueAt).coerceAtLeast(0L)
                lateSum += lateness.toDouble()

                val packet = LabPacket.encode(
                    type = LabPacket.TYPE_DATA,
                    sessionId = sessionId,
                    sequence = n.toInt(),
                    monotonicNanos = now,
                    wallMillis = currentTimeMillis(),
                    payload = payload,
                )

                val result = socket.send(packet)
                if (result.isSuccess) {
                    sent++
                    if (lastSendNanos != 0L) {
                        val gap = (now - lastSendNanos).toDouble()
                        intervalCount++
                        intervalSum += gap
                        intervalSumSq += gap * gap
                        if (gap > maxGapNanos) maxGapNanos = gap.toLong()
                    }
                    lastSendNanos = now
                    onSample(TrafficSample(n.toInt(), now, lateness, packet.size))
                } else {
                    failed++
                    // A send failure is usually the network dropping under us (AP left, interface
                    // changed). Report it; do not abort — the session's value is the record of
                    // what happened, including the failure.
                    if (failed % 25L == 1L) {
                        Napier.w("[lab] send failed (${failed}): ${result.exceptionOrNull()?.message}")
                    }
                }

                if (n % 32L == 0L || n == totalPackets - 1) {
                    _stats.value = snapshot(
                        profile, origin, now, sent, failed, totalPackets,
                        intervalCount, intervalSum, intervalSumSq, maxGapNanos, lateSum,
                    )
                }
            }

            _stats.value = snapshot(
                profile, origin, monotonicNanos(), sent, failed, totalPackets,
                intervalCount, intervalSum, intervalSumSq, maxGapNanos, lateSum,
            ).copy(isComplete = true)
            _isEmitting.value = false
        }
        return Result.success(Unit)
    }

    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        _isEmitting.value = false
        _stats.value = _stats.value.copy(isComplete = true)
    }

    /**
     * Clear the emission counters at session start.
     *
     * [stop] deliberately preserves the final counts so the just-ended session can be summarised,
     * and [start] only re-initialises them *inside* the coroutine it launches — which never runs
     * for a witness-only session. Without this reset, a non-emitting session inherits the previous
     * session's `packetsSent` / `achievedRateHz` / `intervalCv` / `maxGapMillis` into its sidecar
     * (LabInstrument reads `stats.value` unconditionally when building `SessionSummary`), silently
     * attributing one session's illumination to another. Flipping between emitting and
     * witness-only on one handset is the expected path, not an edge case.
     *
     * Called beside `clockSync.reset()` at session start; the same class of cross-session leak.
     */
    fun reset() {
        _stats.value = TrafficStats.IDLE
    }

    private fun snapshot(
        profile: TrafficProfile,
        origin: Long,
        now: Long,
        sent: Long,
        failed: Long,
        target: Long,
        intervalCount: Long,
        intervalSum: Double,
        intervalSumSq: Double,
        maxGapNanos: Long,
        lateSum: Double,
    ): TrafficStats {
        val elapsedSeconds = (now - origin) / 1_000_000_000.0
        val meanInterval = if (intervalCount > 0) intervalSum / intervalCount else 0.0
        val variance = if (intervalCount > 1) {
            (intervalSumSq / intervalCount - meanInterval * meanInterval).coerceAtLeast(0.0)
        } else {
            0.0
        }
        val cv = if (meanInterval > 0) sqrt(variance) / meanInterval else 0.0
        return TrafficStats(
            commandedRateHz = profile.rateHz,
            achievedRateHz = if (elapsedSeconds > 0) sent / elapsedSeconds else 0.0,
            sent = sent,
            failed = failed,
            targetPackets = target,
            meanIntervalMillis = meanInterval / 1_000_000.0,
            intervalCv = cv,
            maxGapMillis = maxGapNanos / 1_000_000.0,
            meanLatenessMillis = if (sent > 0) lateSum / sent / 1_000_000.0 else 0.0,
            startedAtNanos = origin,
            elapsedSeconds = elapsedSeconds,
            isComplete = false,
        )
    }
}

/** One emitted packet, kept for the per-session send-time log. */
data class TrafficSample(
    val sequence: Int,
    val sentAtMonotonicNanos: Long,
    val latenessNanos: Long,
    val sizeBytes: Int,
)

/**
 * Live emission statistics. [intervalCv] is the number the illuminator contract turns on: Doppler
 * features need CV ≲ 0.5, and EXP-P2's broadcast source measured 1.6–2.5.
 */
data class TrafficStats(
    val commandedRateHz: Double,
    val achievedRateHz: Double,
    val sent: Long,
    val failed: Long,
    val targetPackets: Long,
    val meanIntervalMillis: Double,
    val intervalCv: Double,
    val maxGapMillis: Double,
    val meanLatenessMillis: Double,
    val startedAtNanos: Long,
    val elapsedSeconds: Double,
    val isComplete: Boolean,
) {
    val deliveredFraction: Double
        get() = if (commandedRateHz > 0 && elapsedSeconds > 0) {
            achievedRateHz / commandedRateHz
        } else {
            0.0
        }

    /** The on-device half of the H2 gate: uniform enough to be a sampling axis. */
    val meetsUniformityGate: Boolean get() = sent > 100 && intervalCv <= 0.2

    companion object {
        val IDLE = TrafficStats(
            commandedRateHz = 0.0,
            achievedRateHz = 0.0,
            sent = 0,
            failed = 0,
            targetPackets = 0,
            meanIntervalMillis = 0.0,
            intervalCv = 0.0,
            maxGapMillis = 0.0,
            meanLatenessMillis = 0.0,
            startedAtNanos = 0,
            elapsedSeconds = 0.0,
            isComplete = false,
        )
    }
}
