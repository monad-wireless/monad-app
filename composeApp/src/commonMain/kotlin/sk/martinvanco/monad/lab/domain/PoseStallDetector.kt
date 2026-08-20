package sk.martinvanco.monad.lab.domain

/**
 * Detects when the pose stream stops advancing, so the artefact records *when* the tracker was not
 * producing rather than only that the delivered rate was low.
 *
 * Why the existing checks cannot do this: the poller skips frames whose timestamp has not advanced
 * (the 2026-08-19 duplicate fix), so a stalled tracker now looks like a *slow* stream — and the
 * health monitor's delivered-rate figure smears a twenty-second hole over the whole session instead
 * of naming it. Walk B carried exactly that hole, at t+34 s, and nothing in its artefacts says so.
 *
 * Pure and clock-fed, so the trap it guards is pinned by a test rather than by a walk. The
 * instrument's heartbeat feeds it the current time and the timestamp of the last accepted pose; it
 * answers with at most one transition per call.
 */
class PoseStallDetector(
    commandedRateHz: Double,
) {
    /**
     * How long the stream may sit still before it is a stall.
     *
     * Three commanded periods or [MIN_THRESHOLD_NANOS], whichever is larger: at 10 Hz three missed
     * polls is jitter, two seconds is not — and at 2 Hz three periods is already 1.5 s, so the floor
     * keeps the two rates on comparable footing. ARKit legitimately holds a frame for a beat while
     * `limited`; the floor keeps that out of the marker stream.
     */
    val thresholdNanos: Long = maxOf(
        (3_000_000_000.0 / commandedRateHz.coerceAtLeast(0.1)).toLong(),
        MIN_THRESHOLD_NANOS,
    )

    private var stalled = false

    /** What just happened to the stream, if anything. */
    sealed interface Transition {
        /** No poses for [gapNanos] and counting. Raised once per stall. */
        data class Stalled(val gapNanos: Long) : Transition

        /** Poses are arriving again after a stall of [gapNanos]. */
        data class Resumed(val gapNanos: Long) : Transition
    }

    /**
     * Evaluate the stream at [nowNanos].
     *
     * [lastAdvanceNanos] is the monotonic stamp of the last accepted pose, or the tracking start
     * when none has arrived yet — a tracker that starts and never produces is stalled from the
     * start, and that is the honest reading of it.
     */
    fun evaluate(nowNanos: Long, lastAdvanceNanos: Long): Transition? {
        val gap = nowNanos - lastAdvanceNanos
        return when {
            !stalled && gap > thresholdNanos -> {
                stalled = true
                Transition.Stalled(gap)
            }

            stalled && gap <= thresholdNanos -> {
                stalled = false
                Transition.Resumed(gap)
            }

            else -> null
        }
    }

    /**
     * Called when a fresh pose arrives, so a stall that ended between heartbeats still reports the
     * gap it actually had rather than the sub-threshold remainder the next heartbeat would see.
     */
    fun onAdvance(nowNanos: Long, previousAdvanceNanos: Long): Transition? {
        if (!stalled) return null
        stalled = false
        return Transition.Resumed(nowNanos - previousAdvanceNanos)
    }

    private companion object {
        const val MIN_THRESHOLD_NANOS = 2_000_000_000L
    }
}
