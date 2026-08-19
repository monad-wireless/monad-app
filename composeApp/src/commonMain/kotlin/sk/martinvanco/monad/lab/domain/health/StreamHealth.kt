package sk.martinvanco.monad.lab.domain.health

/**
 * Per-stream liveness: the answer to "is this session actually being recorded?".
 *
 * The lab-readiness audit's hard lesson was not a crash. An overnight capture ran to completion,
 * reported itself healthy throughout, and had silently collapsed to **11.6 % delivery for 42
 * minutes**. Nothing threw, nothing logged, and the loss was found in analysis weeks later. Loud
 * failure is cheap; silent degradation is what costs a session.
 *
 * So this package models *degradation* as a first-class state alongside silence, and it accounts
 * for **time spent in each state** rather than only the value at the moment somebody looked. A
 * stream that was dead for forty minutes and healthy at `stop()` must not be able to report itself
 * as healthy.
 *
 * Everything here is pure: no clock, no I/O, no coroutines. Time is always passed in. That is what
 * makes the thresholds testable without a lab.
 */

/** The streams whose liveness decides whether a session is worth anything. */
enum class LabStream(val artefact: String, val label: String) {
    /** Paced UDP emission — `traffic.tsv`. The only stream with a *commanded* rate to fail against. */
    ILLUMINATOR("traffic.tsv", "Illuminator"),

    /** Anchor sightings — `beacons.tsv`. */
    WITNESS("beacons.tsv", "Witness"),

    /** Zone enter/exit derived from the witness — `transitions.tsv`. Event-driven. */
    TRANSITIONS("transitions.tsv", "Zone transitions"),

    /** Participant scans — `ground_truth.tsv`. Event-driven, and the only people-counting stream. */
    GROUND_TRUTH("ground_truth.tsv", "Ground truth"),

    /** Collector time exchanges — `clock.tsv`. Gate G4 depends on this one existing at all. */
    CLOCK("clock.tsv", "Clock sync"),

    /**
     * The pose track — `pose.tsv`. The second stream with a *commanded* rate to fall short of.
     *
     * Watched for the same reason as the illuminator, and it is the more insidious of the two.
     * Odometry does not stop when it fails, it degrades: the platform keeps returning positions
     * while it is lost, so a walk can look recorded end to end and be geometrically worthless. A
     * commanded rate turns that into a number.
     */
    POSE("pose.tsv", "Pose track"),
}

/**
 * Health of one stream.
 *
 * [DEGRADED] is the state this whole module exists for: the stream is producing, so nothing looks
 * broken, but it is producing far less than it was told to.
 */
enum class StreamState {
    /** The session does not play this role — a witness-only participant has no illuminator. */
    NOT_APPLICABLE,

    /** Applicable, nothing yet, and still inside its grace period. */
    IDLE,

    /** Producing at or near the expected pace. */
    ALIVE,

    /** Producing, but materially below the commanded pace. The 11.6 %-delivery state. */
    DEGRADED,

    /** Nothing recently. Recoverable; usually a backgrounding or an association wobble. */
    STALE,

    /** Nothing for long enough that the session should be considered not recording this stream. */
    DEAD,
    ;

    /** Ordering for "what is the worst thing happening right now". Higher is worse. */
    val severity: Int
        get() = when (this) {
            NOT_APPLICABLE -> 0
            ALIVE -> 0
            IDLE -> 1
            DEGRADED -> 2
            STALE -> 3
            DEAD -> 4
        }

    val isHealthy: Boolean get() = severity <= 1

    val wire: String get() = name.lowercase()
}

/**
 * Thresholds for one stream.
 *
 * @param expectedRateHz the commanded pace, when there is one. `null` means the stream is
 *   event-driven and has no rate to fail against — a zone transition is not late, it simply has not
 *   happened.
 * @param staleAfterMillis silence beyond which the stream is [StreamState.STALE].
 * @param deadAfterMillis silence beyond which it is [StreamState.DEAD].
 * @param degradedBelowFraction fraction of [expectedRateHz] below which a *producing* stream counts
 *   as [StreamState.DEGRADED]. 0.5 by default: half the commanded rate is already unusable for the
 *   Doppler leg, and 11.6 % is far past it.
 * @param silenceIsFailure false for event-driven streams, which may legitimately be silent for the
 *   whole session.
 * @param rateWindowMillis width of the sliding window the observed rate is measured over. Also the
 *   warm-up: [StreamState.DEGRADED] is not raised before one full window has elapsed, so a stream
 *   is never accused of being slow on the strength of a partially filled window.
 */
data class StreamPolicy(
    val expectedRateHz: Double? = null,
    val staleAfterMillis: Long,
    val deadAfterMillis: Long,
    val degradedBelowFraction: Double = 0.5,
    val silenceIsFailure: Boolean = true,
    val rateWindowMillis: Long = 10_000,
) {
    init {
        require(staleAfterMillis > 0) { "staleAfterMillis must be positive" }
        require(deadAfterMillis >= staleAfterMillis) { "dead threshold must not precede stale" }
    }
}

/**
 * The default thresholds, with their reasons.
 *
 * These are deliberately derived from the commanded configuration rather than hard-coded seconds:
 * a 1 Hz profile and a 200 Hz profile have nothing in common except that both should keep pace.
 */
object StreamPolicies {

    /**
     * Illuminator. Silence for twenty commanded periods is stale, a hundred is dead, with floors so
     * that a slow profile is not declared dead the instant it starts.
     */
    fun illuminator(commandedRateHz: Double): StreamPolicy {
        val periodMillis = if (commandedRateHz > 0.0) (1000.0 / commandedRateHz).toLong() else 1000L
        return StreamPolicy(
            expectedRateHz = commandedRateHz.takeIf { it > 0.0 },
            staleAfterMillis = (periodMillis * 20).coerceIn(2_000L, 30_000L),
            deadAfterMillis = (periodMillis * 100).coerceIn(10_000L, 120_000L),
        )
    }

    /**
     * Witness. No commanded rate: CoreLocation ranging cadence is the OS's business, and a phone
     * genuinely out of range of every anchor produces nothing without anything being wrong. So this
     * stream is judged by silence only, and generously — the [sk.martinvanco.monad.lab.domain.ZoneTracker]
     * already treats 15 s of quiet as an exit, so 30 s is the first moment silence is news.
     */
    fun witness(): StreamPolicy = StreamPolicy(
        expectedRateHz = null,
        staleAfterMillis = 30_000,
        deadAfterMillis = 120_000,
    )

    /** Zone transitions: event-driven. A session with no transitions is a participant who did not move. */
    fun transitions(): StreamPolicy = StreamPolicy(
        expectedRateHz = null,
        staleAfterMillis = 60_000,
        deadAfterMillis = 600_000,
        silenceIsFailure = false,
    )

    /** Ground truth: event-driven, and driven by a human, so silence is never a fault of the app. */
    fun groundTruth(): StreamPolicy = StreamPolicy(
        expectedRateHz = null,
        staleAfterMillis = 60_000,
        deadAfterMillis = 600_000,
        silenceIsFailure = false,
    )

    /**
     * Pose track. Silence for ten commanded periods is stale, sixty is dead.
     *
     * Tighter than the illuminator's twenty and a hundred, because a pose gap is not recoverable by
     * analysis the way a dropped datagram is: the datagram had no information the next one lacks,
     * whereas the missing metres of a trajectory cannot be reconstructed from the metres either side
     * unless the walk happened to be straight. So this stream is judged sooner, while the operator
     * is still holding the phone and can restart it.
     */
    fun pose(commandedRateHz: Double): StreamPolicy {
        val periodMillis = if (commandedRateHz > 0.0) (1000.0 / commandedRateHz).toLong() else 1000L
        return StreamPolicy(
            expectedRateHz = commandedRateHz.takeIf { it > 0.0 },
            staleAfterMillis = (periodMillis * 10).coerceIn(2_000L, 15_000L),
            deadAfterMillis = (periodMillis * 60).coerceIn(10_000L, 60_000L),
        )
    }

    /**
     * Clock bursts. One and a half resync periods of silence is stale, three is dead — a missed
     * burst is usually the socket having quietly stopped reaching the collector, which is exactly
     * the failure gate G4 turns on.
     */
    fun clock(resyncSeconds: Int): StreamPolicy {
        val period = (resyncSeconds.coerceAtLeast(30)) * 1000L
        return StreamPolicy(
            expectedRateHz = null,
            staleAfterMillis = (period * 3) / 2,
            deadAfterMillis = period * 3,
        )
    }
}

/**
 * A counting window over fixed buckets.
 *
 * Bucketed rather than a list of timestamps because the illuminator runs at up to 1 kHz: a ten
 * second window would otherwise hold ten thousand entries and be rebuilt on every sample. Ten
 * buckets of one second each is O(1) per update and accurate to the second, which is all a health
 * display needs.
 */
internal class RateWindow(
    private val bucketMillis: Long = 1_000,
    private val bucketCount: Int = 10,
) {
    private val counts = LongArray(bucketCount)
    private val epochs = LongArray(bucketCount) { Long.MIN_VALUE }

    val windowMillis: Long get() = bucketMillis * bucketCount

    fun add(atMillis: Long, count: Long) {
        if (count <= 0) return
        val epoch = atMillis / bucketMillis
        val index = ((epoch % bucketCount) + bucketCount).toInt() % bucketCount
        if (epochs[index] != epoch) {
            epochs[index] = epoch
            counts[index] = 0
        }
        counts[index] += count
    }

    /** Events per second over the trailing window, counting only buckets still inside it. */
    fun ratePerSecond(nowMillis: Long): Double {
        val oldestEpoch = (nowMillis / bucketMillis) - (bucketCount - 1)
        var total = 0L
        for (i in 0 until bucketCount) {
            if (epochs[i] >= oldestEpoch) total += counts[i]
        }
        return total.toDouble() / (windowMillis / 1000.0)
    }
}

/** A stream's health at one instant, including what it has been through. */
data class StreamHealth(
    val stream: LabStream,
    val state: StreamState,
    val eventsPerSecond: Double,
    val expectedRateHz: Double?,
    val totalEvents: Long,
    /** Milliseconds since the last event, or since the stream started when there has never been one. */
    val silenceMillis: Long,
    val everProduced: Boolean,
    /** The worst state this stream has been in during the session. Cannot be un-seen. */
    val worstState: StreamState,
    val millisDegraded: Long,
    val millisStale: Long,
    val millisDead: Long,
) {
    /** Delivered ÷ commanded, or null for an event-driven stream. */
    val deliveredFraction: Double?
        get() = expectedRateHz?.takeIf { it > 0.0 }?.let { eventsPerSecond / it }

    /** True when the stream spent any time degraded, stale or dead — the screenshot question. */
    val hadTrouble: Boolean get() = worstState.severity >= StreamState.DEGRADED.severity

    val troubleMillis: Long get() = millisDegraded + millisStale + millisDead
}

/**
 * Tracks one stream from an **absolute counter**, sampled on a heartbeat.
 *
 * Deliberately not fed per event. The illuminator's send loop is the scientific instrument, and the
 * least invasive way to watch it is to read the counter it already maintains once a second rather
 * than to add work inside it. That also makes the whole thing a pure function of (counter, time),
 * which is why it can be tested exhaustively.
 *
 * @param startedAtMillis monotonic milliseconds at which the stream became applicable.
 */
class StreamHealthTracker(
    val stream: LabStream,
    val policy: StreamPolicy,
    private val startedAtMillis: Long,
) {
    private val window = RateWindow(bucketCount = (policy.rateWindowMillis / 1_000L).toInt().coerceAtLeast(1))

    private var total: Long = 0
    private var lastEventAtMillis: Long? = null
    private var lastAccrualMillis: Long = startedAtMillis

    /**
     * The worst *trouble* state seen, or null if the stream has only ever been idle or alive.
     *
     * Deliberately not seeded with [StreamState.IDLE]. Every stream begins idle, so a worst-ever
     * field that counted it would read IDLE for a perfectly healthy session and make the one thing
     * this field exists to report — did anything go wrong at any point — unreadable.
     */
    private var worstTrouble: StreamState? = null

    private var degraded: Long = 0
    private var stale: Long = 0
    private var dead: Long = 0

    /**
     * Fold in the stream's counter as of [nowMillis], accruing the time since the previous call
     * into whichever state the stream was in over that interval.
     *
     * Time is charged to the state *before* the new counter is applied, because that is the state
     * the stream was actually in during the elapsed interval.
     */
    fun observe(counter: Long, nowMillis: Long) {
        accrue(nowMillis)
        val delta = counter - total
        if (delta > 0) {
            total = counter
            lastEventAtMillis = nowMillis
            window.add(nowMillis, delta)
        } else if (counter < total) {
            // A counter that went backwards means a new session reused this tracker; treat it as a
            // fresh start rather than silently reporting a negative rate.
            total = counter
        }
    }

    /** Charge elapsed time to the current state without touching the counter. */
    fun accrue(nowMillis: Long) {
        if (nowMillis <= lastAccrualMillis) return
        val elapsed = nowMillis - lastAccrualMillis
        when (currentState(lastAccrualMillis)) {
            StreamState.DEGRADED -> degraded += elapsed
            StreamState.STALE -> stale += elapsed
            StreamState.DEAD -> dead += elapsed
            else -> Unit
        }
        lastAccrualMillis = nowMillis
    }

    fun snapshot(nowMillis: Long): StreamHealth {
        val state = currentState(nowMillis)
        if (state.severity >= StreamState.DEGRADED.severity &&
            state.severity > (worstTrouble?.severity ?: -1)
        ) {
            worstTrouble = state
        }
        return StreamHealth(
            stream = stream,
            state = state,
            eventsPerSecond = window.ratePerSecond(nowMillis),
            expectedRateHz = policy.expectedRateHz,
            totalEvents = total,
            silenceMillis = (nowMillis - (lastEventAtMillis ?: startedAtMillis)).coerceAtLeast(0),
            everProduced = lastEventAtMillis != null,
            worstState = worstTrouble ?: state,
            millisDegraded = degraded,
            millisStale = stale,
            millisDead = dead,
        )
    }

    private fun currentState(atMillis: Long): StreamState = StreamHealthRules.evaluate(
        policy = policy,
        totalEvents = total,
        silenceMillis = (atMillis - (lastEventAtMillis ?: startedAtMillis)).coerceAtLeast(0),
        elapsedSinceStartMillis = (atMillis - startedAtMillis).coerceAtLeast(0),
        observedRateHz = window.ratePerSecond(atMillis),
    )
}

/** The threshold logic, extracted so it can be tested without constructing a tracker. */
object StreamHealthRules {

    fun evaluate(
        policy: StreamPolicy,
        totalEvents: Long,
        silenceMillis: Long,
        elapsedSinceStartMillis: Long,
        observedRateHz: Double,
    ): StreamState {
        if (totalEvents <= 0L) {
            if (!policy.silenceIsFailure) return StreamState.IDLE
            return when {
                silenceMillis >= policy.deadAfterMillis -> StreamState.DEAD
                silenceMillis >= policy.staleAfterMillis -> StreamState.STALE
                else -> StreamState.IDLE
            }
        }

        if (policy.silenceIsFailure) {
            if (silenceMillis >= policy.deadAfterMillis) return StreamState.DEAD
            if (silenceMillis >= policy.staleAfterMillis) return StreamState.STALE
        }

        val expected = policy.expectedRateHz
        // Warm-up: the sliding window is not full yet, so a low rate is an artefact of the window,
        // not of the stream. Accusing a healthy illuminator in its first ten seconds would train an
        // operator to ignore the one indicator that matters.
        if (expected != null && expected > 0.0 && elapsedSinceStartMillis >= policy.rateWindowMillis) {
            if (observedRateHz < expected * policy.degradedBelowFraction) return StreamState.DEGRADED
        }
        return StreamState.ALIVE
    }
}
