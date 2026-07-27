package sk.martinvanco.monad.lab.domain

/**
 * The device's **sleep-continuous** monotonic clock, in nanoseconds from an arbitrary origin.
 *
 * This must be the clock that keeps advancing while the device is asleep — `CLOCK_MONOTONIC_RAW`
 * on Darwin, `SystemClock.elapsedRealtimeNanos()` on Android. The uptime-style clocks that stop
 * during sleep would silently *compress* a backgrounded session's timeline, which is precisely the
 * condition every field session runs in.
 */
expect fun monotonicNanos(): Long

/**
 * Identifier for the current continuity epoch. Changes across reboot (and across process restart
 * where the platform cannot distinguish the two), which invalidates monotonic continuity: a
 * "session" that spans a boundary is two sessions, and analysis must treat it that way.
 */
expect fun clockBootId(): String

/** Human-readable name of the clock source actually used, recorded into the session sidecar. */
expect fun clockSourceName(): String

/**
 * One four-timestamp exchange with the collector.
 *
 * ```
 * t1  phone monotonic at send
 * t2  collector reference at receive
 * t3  collector reference at send
 * t4  phone monotonic at receive
 * ```
 */
data class ClockExchange(
    val t1Nanos: Long,
    val t2Nanos: Long,
    val t3Nanos: Long,
    val t4Nanos: Long,
) {
    /** Offset of the collector's reference clock relative to this device's monotonic clock. */
    val offsetNanos: Long get() = ((t2Nanos - t1Nanos) + (t3Nanos - t4Nanos)) / 2

    /** Round-trip delay excluding the collector's own turnaround. */
    val delayNanos: Long get() = (t4Nanos - t1Nanos) - (t3Nanos - t2Nanos)
}

/**
 * The estimate the session carries. `offsetNanos` maps device-monotonic → collector-reference:
 *
 * ```
 * reference_ns ≈ monotonic_ns + offsetNanos + skewPpm * 1e-6 * (monotonic_ns - anchorNanos)
 * ```
 *
 * The offset is *stored*, never applied to the timestamps the device writes. Analysis applies it,
 * so the correction stays auditable and reversible — the same discipline `csid` follows on the
 * observer side.
 */
data class ClockEstimate(
    val offsetNanos: Long,
    val delayNanos: Long,
    val skewPpm: Double,
    val anchorNanos: Long,
    val samples: Int,
) {
    val offsetMillis: Double get() = offsetNanos / 1_000_000.0
    val delayMillis: Double get() = delayNanos / 1_000_000.0

    /** Project a device monotonic stamp onto the collector's reference timeline. */
    fun toReferenceNanos(deviceMonotonicNanos: Long): Long {
        val drift = (skewPpm * 1e-6 * (deviceMonotonicNanos - anchorNanos)).toLong()
        return deviceMonotonicNanos + offsetNanos + drift
    }

    companion object {
        val UNSYNCED = ClockEstimate(0, 0, 0.0, 0, 0)
    }
}

/**
 * The estimator, kept pure so it is testable without a socket.
 *
 * A burst of exchanges is reduced by **keeping the minimum-delay sample** — the classic NTP /
 * Cristian filter. On a jittery wireless link the minimum-delay sample is the one least
 * contaminated by queueing, and averaging would be strictly worse: queueing delay is one-sided
 * noise, so its mean is a bias, not a wobble.
 */
object ClockEstimator {

    /** Reduce one burst to a single estimate. Returns null when the burst is empty. */
    fun fromBurst(exchanges: List<ClockExchange>, previous: ClockEstimate? = null): ClockEstimate? {
        val best = exchanges.minByOrNull { it.delayNanos } ?: return null
        val anchor = best.t4Nanos
        val skew = previous?.let { prev ->
            val dt = anchor - prev.anchorNanos
            // Below a minute of separation the skew fit is dominated by offset noise; keep the
            // previous value rather than injecting a wild slope.
            if (dt > 60_000_000_000L) {
                ((best.offsetNanos - prev.offsetNanos).toDouble() / dt.toDouble()) * 1e6
            } else {
                prev.skewPpm
            }
        } ?: 0.0
        return ClockEstimate(
            offsetNanos = best.offsetNanos,
            delayNanos = best.delayNanos,
            skewPpm = skew,
            anchorNanos = anchor,
            samples = exchanges.size,
        )
    }

    /**
     * Residual of a held-out exchange against an estimate — the acceptance check for the clock
     * gate (target: under 5 ms over a 30-minute session).
     */
    fun residualNanos(estimate: ClockEstimate, heldOut: ClockExchange): Long {
        val predictedOffset = estimate.toReferenceNanos(heldOut.t4Nanos) - heldOut.t4Nanos
        return heldOut.offsetNanos - predictedOffset
    }
}
