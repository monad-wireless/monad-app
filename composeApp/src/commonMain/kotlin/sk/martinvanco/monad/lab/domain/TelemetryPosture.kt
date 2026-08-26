package sk.martinvanco.monad.lab.domain

/**
 * What the handset's telemetry courier is doing, in the terms an operator reads.
 *
 * WHY IT EXISTS AS A TYPE. `LabTelemetryShipper` was written to stay silent when the lab bundle
 * carried no collector endpoint, and the reasoning was sound — "nothing is more confusing than a
 * phone retrying a host that was never meant to exist". The consequence was not: on 2026-08-26 a
 * 21-minute survey walk produced zero `{service_name="monad-app"}` lines in Loki while `log.tsv`
 * uploaded normally, and an unconfigured courier was indistinguishable from a working one. A walk is
 * then unobservable until it uploads, which is exactly the case a lost upload cannot satisfy.
 *
 * Silence about a *host* is right. Silence about the *courier's own posture* is not.
 *
 * [lastError] is the last failure, retained rather than cleared on the next success, because the
 * question an operator asks at the bench is "has this ever worked", and a field cleared by one lucky
 * flush cannot answer it. [flushes] and [samplesShipped] say whether the answer is yes.
 */
data class TelemetryPosture(
    /** True when the bundle names an endpoint. False means the courier is doing nothing, by design. */
    val configured: Boolean = false,
    /** Host of the collector, for the console line. Empty when unconfigured. */
    val endpoint: String = "",
    /** Successful flushes this app run. Zero with `configured = true` is the interesting state. */
    val flushes: Int = 0,
    val samplesShipped: Int = 0,
    /** Samples evicted un-shipped. A gap admitted rather than hidden. */
    val dropped: Int = 0,
    /** The last failure seen, retained across later successes. */
    val lastError: String? = null,
    /** Wall clock of the last successful flush, or 0. */
    val lastFlushWallMillis: Long = 0,
    /**
     * Flushes that have failed in a row. Drives the backoff, and says whether the courier is
     * *trying and failing* or simply between attempts.
     */
    val consecutiveFailures: Int = 0,
    /** How long until the next attempt, under backoff. 0 when the courier is healthy. */
    val nextAttemptInMillis: Long = 0,
) {
    /** The one line the console shows. */
    val line: String
        get() = when {
            !configured ->
                "NOT SHIPPING — the lab bundle carries no telemetry endpoint, so this walk is " +
                    "invisible on the dashboard until it uploads"

            flushes == 0 && lastError != null ->
                "configured ($endpoint) but FAILING: $lastError" + backoffSuffix

            flushes == 0 -> "configured ($endpoint), nothing shipped yet"
            lastError != null ->
                "$samplesShipped sample(s) in $flushes flush(es) to $endpoint, $dropped dropped — " +
                    "last error: $lastError" + backoffSuffix

            else -> "$samplesShipped sample(s) in $flushes flush(es) to $endpoint, $dropped dropped"
        }

    /**
     * What the backoff is doing, appended to the line.
     *
     * A courier that is *waiting* looks identical to one that is *stuck* unless it says so, and the
     * difference is the whole reason the backoff exists — an operator who cannot tell them apart
     * reads a growing failure count as an app that is spinning.
     */
    private val backoffSuffix: String
        get() = if (consecutiveFailures > 0 && nextAttemptInMillis > 0) {
            " ($consecutiveFailures in a row, next try in ${nextAttemptInMillis / 1000} s)"
        } else {
            ""
        }

    /** True when the courier is doing what it was asked to do. Drives one colour, nothing else. */
    val healthy: Boolean get() = configured && flushes > 0 && lastError == null && consecutiveFailures == 0

    companion object {
        /** Before the shipper has said anything. Distinct from "not configured", which is a finding. */
        val UNKNOWN = TelemetryPosture()
    }
}
