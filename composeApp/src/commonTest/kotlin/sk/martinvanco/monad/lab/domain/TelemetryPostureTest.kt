package sk.martinvanco.monad.lab.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the telemetry courier says about itself.
 *
 * The whole type exists because silence was the wrong behaviour. On 2026-08-26 a 21-minute survey
 * walk produced zero `{service_name="monad-app"}` lines in Loki while `log.tsv` uploaded normally,
 * and an unconfigured courier was indistinguishable from a working one. Every case below is a state
 * an operator has to be able to tell apart at a bench, in seconds.
 */
class TelemetryPostureTest {

    @Test
    fun anUnconfiguredCourierSaysSoRatherThanLookingHealthy() {
        val line = TelemetryPosture(configured = false).line
        assertTrue(line.contains("NOT SHIPPING"), line)
        assertTrue(line.contains("invisible on the dashboard"), line)
        assertFalse(TelemetryPosture(configured = false).healthy)
    }

    @Test
    fun configuredAndSilentIsItsOwnState() {
        // The middle state, and the one that used to be unreachable. "Configured" is not "working":
        // the bundle on the server was correct on 2026-08-26 and the handset shipped nothing.
        val posture = TelemetryPosture(configured = true, endpoint = "https://otlp.example:4319")
        assertTrue(posture.line.contains("nothing shipped yet"), posture.line)
        assertFalse(posture.healthy)
    }

    @Test
    fun aFailingCourierNamesTheErrorAndWhenItWillTryAgain() {
        // A courier that is WAITING looks identical to one that is STUCK unless it says so, and an
        // operator who cannot tell them apart reads a growing failure count as an app that is
        // spinning. That is exactly how the 15 s timeout / 15 s interval pair was read.
        val posture = TelemetryPosture(
            configured = true,
            endpoint = "https://otlp.example:4319",
            lastError = "Request timeout has expired",
            consecutiveFailures = 3,
            nextAttemptInMillis = 120_000,
        )
        assertTrue(posture.line.contains("Request timeout"), posture.line)
        assertTrue(posture.line.contains("3 in a row"), posture.line)
        assertTrue(posture.line.contains("next try in 120 s"), posture.line)
        assertFalse(posture.healthy)
    }

    @Test
    fun aWorkingCourierIsQuietAndCountsWhatItSent() {
        val posture = TelemetryPosture(
            configured = true,
            endpoint = "https://otlp.example:4319",
            flushes = 4,
            samplesShipped = 240,
        )
        assertEquals("240 sample(s) in 4 flush(es) to https://otlp.example:4319, 0 dropped", posture.line)
        assertTrue(posture.healthy)
    }

    @Test
    fun aPastFailureIsRetainedButDoesNotReadAsStillBackingOff() {
        // `lastError` is deliberately never cleared — the bench question is "has this EVER failed",
        // and a field one lucky flush wipes cannot answer it. The backoff counters ARE cleared,
        // because those describe what happens next rather than what happened.
        val posture = TelemetryPosture(
            configured = true,
            endpoint = "https://otlp.example:4319",
            flushes = 9,
            samplesShipped = 540,
            lastError = "Request timeout has expired",
            consecutiveFailures = 0,
            nextAttemptInMillis = 0,
        )
        assertTrue(posture.line.contains("last error:"), posture.line)
        assertFalse(posture.line.contains("in a row"), posture.line)
        assertFalse(posture.healthy, "a recorded failure keeps the colour honest")
    }

    @Test
    fun droppedSamplesAreReportedRatherThanHidden() {
        // The buffer evicts OLDEST-first at ten minutes, bounded by Mimir's out-of-order window. A
        // sample older than that could not be placed at the time it was taken, so the shipper admits
        // the gap instead of silently misplacing history.
        val posture = TelemetryPosture(
            configured = true,
            endpoint = "https://otlp.example:4319",
            flushes = 2,
            samplesShipped = 600,
            dropped = 143,
        )
        assertTrue(posture.line.contains("143 dropped"), posture.line)
    }
}
