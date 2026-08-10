package sk.martinvanco.monad.lab.domain.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The thresholds, checked against the failure they exist for.
 *
 * The motivating incident is [degradedIsRaisedForTheDeliveryCollapseThatWentUnnoticed] — a stream
 * that never stopped, never errored, and delivered 11.6 % of its commanded rate for 42 minutes.
 * Silence detection alone would have called that healthy for the whole window.
 */
class StreamHealthTest {

    private val illuminator = StreamPolicies.illuminator(commandedRateHz = 100.0)

    @Test
    fun aStreamKeepingPaceIsAlive() {
        val tracker = StreamHealthTracker(LabStream.ILLUMINATOR, illuminator, startedAtMillis = 0)
        // 100 events per second, for thirty seconds.
        for (second in 1..30) tracker.observe(counter = second * 100L, nowMillis = second * 1000L)
        val snapshot = tracker.snapshot(30_000)

        assertEquals(StreamState.ALIVE, snapshot.state)
        assertEquals(3000L, snapshot.totalEvents)
        assertEquals(100.0, snapshot.eventsPerSecond, absoluteTolerance = 1.0)
        assertEquals(StreamState.ALIVE, snapshot.worstState)
        assertEquals(0L, snapshot.troubleMillis)
    }

    @Test
    fun degradedIsRaisedForTheDeliveryCollapseThatWentUnnoticed() {
        val tracker = StreamHealthTracker(LabStream.ILLUMINATOR, illuminator, startedAtMillis = 0)
        // Twenty seconds at the commanded 100 Hz…
        for (second in 1..20) tracker.observe(second * 100L, second * 1000L)
        assertEquals(StreamState.ALIVE, tracker.snapshot(20_000).state)

        // …then 11.6 Hz, which is what the overnight capture actually delivered. Events keep
        // arriving throughout, so nothing is ever silent and no silence threshold would fire.
        var counter = 2000L
        for (second in 21..80) {
            counter += 12
            tracker.observe(counter, second * 1000L)
        }
        val snapshot = tracker.snapshot(80_000)

        assertEquals(StreamState.DEGRADED, snapshot.state)
        assertTrue(snapshot.everProduced, "the stream never went silent")
        assertEquals(0L, snapshot.silenceMillis, "and was producing at the moment of the snapshot")
        assertTrue(
            (snapshot.deliveredFraction ?: 1.0) < 0.2,
            "delivered fraction should show the collapse, was ${snapshot.deliveredFraction}",
        )
        assertTrue(
            snapshot.millisDegraded > 40_000,
            "time-in-state must accumulate; was ${snapshot.millisDegraded} ms",
        )
    }

    @Test
    fun recoveryDoesNotEraseHistory() {
        val tracker = StreamHealthTracker(LabStream.ILLUMINATOR, illuminator, startedAtMillis = 0)
        for (second in 1..15) tracker.observe(second * 100L, second * 1000L)
        // A gap long enough to be dead…
        tracker.observe(1500L, 60_000)
        assertEquals(StreamState.DEAD, tracker.snapshot(60_000).state)
        // …then full recovery.
        var counter = 1500L
        for (second in 61..120) {
            counter += 100
            tracker.observe(counter, second * 1000L)
        }
        val snapshot = tracker.snapshot(120_000)

        assertEquals(StreamState.ALIVE, snapshot.state, "it is fine now")
        assertEquals(StreamState.DEAD, snapshot.worstState, "but it was not, and that is recorded")
        assertTrue(snapshot.hadTrouble)
        assertTrue(snapshot.millisDead > 0 || snapshot.millisStale > 0)
    }

    @Test
    fun silenceCrossesStaleThenDead() {
        val tracker = StreamHealthTracker(LabStream.WITNESS, StreamPolicies.witness(), 0)
        tracker.observe(1, 1_000)

        assertEquals(StreamState.ALIVE, tracker.snapshot(5_000).state)
        assertEquals(StreamState.STALE, tracker.snapshot(40_000).state)
        assertEquals(StreamState.DEAD, tracker.snapshot(200_000).state)
    }

    @Test
    fun eventDrivenStreamsAreNeverBlamedForSilence() {
        val tracker = StreamHealthTracker(LabStream.GROUND_TRUTH, StreamPolicies.groundTruth(), 0)
        // A participant who never scans is a fact about the participant, not a fault in the app.
        assertEquals(StreamState.IDLE, tracker.snapshot(3_600_000).state)

        tracker.observe(1, 100)
        assertEquals(StreamState.ALIVE, tracker.snapshot(3_600_000).state)
    }

    @Test
    fun degradedIsNotRaisedDuringTheWarmUpWindow() {
        val tracker = StreamHealthTracker(LabStream.ILLUMINATOR, illuminator, startedAtMillis = 0)
        // Two seconds in, the ten-second rate window is 80 % empty and would read ~20 Hz against a
        // commanded 100. Accusing a healthy illuminator here would train the operator to ignore the
        // one indicator that matters.
        tracker.observe(100, 1_000)
        tracker.observe(200, 2_000)
        assertEquals(StreamState.ALIVE, tracker.snapshot(2_000).state)
    }

    @Test
    fun aStreamThatNeverStartsIsDeadNotIdle() {
        val tracker = StreamHealthTracker(LabStream.ILLUMINATOR, illuminator, startedAtMillis = 0)
        assertEquals(StreamState.IDLE, tracker.snapshot(500).state, "grace period")
        assertEquals(StreamState.STALE, tracker.snapshot(5_000).state)
        assertEquals(StreamState.DEAD, tracker.snapshot(30_000).state)
    }

    @Test
    fun thresholdsScaleWithTheCommandedRate() {
        val fast = StreamPolicies.illuminator(200.0)
        val slow = StreamPolicies.illuminator(1.0)
        assertTrue(
            slow.staleAfterMillis > fast.staleAfterMillis,
            "a 1 Hz profile must be given more silence than a 200 Hz one",
        )
        assertTrue(fast.staleAfterMillis >= 2_000, "floors keep a fast profile from flapping")
        assertTrue(slow.deadAfterMillis <= 120_000, "caps keep a slow profile from never failing")
    }

    @Test
    fun severityOrdersTheOverallVerdict() {
        val monitor = SessionHealthMonitor.forSession(
            emitting = true,
            commandedRateHz = 50.0,
            witnessing = true,
            resyncSeconds = 600,
            startedAtMillis = 0,
        )
        // Witness alive, illuminator silent past its dead threshold.
        var health = monitor.tick(StreamCounters(witness = 1), nowMillis = 1_000)
        health = monitor.tick(StreamCounters(witness = 2), nowMillis = 60_000)

        assertEquals(StreamState.DEAD, health.overall)
        assertTrue(health.troubled.any { it.stream == LabStream.ILLUMINATOR })
        assertTrue(!health.wouldPassReview)
    }

    @Test
    fun witnessOnlySessionsHaveNoIlluminatorOrClockToFail() {
        val monitor = SessionHealthMonitor.forSession(
            emitting = false,
            commandedRateHz = 0.0,
            witnessing = true,
            resyncSeconds = 600,
            startedAtMillis = 0,
        )
        val streams = monitor.applicableStreams
        assertTrue(LabStream.ILLUMINATOR !in streams)
        assertTrue(LabStream.CLOCK !in streams)
        assertTrue(LabStream.WITNESS in streams)
        // Ground truth is always applicable: a participant can scan whether or not this phone is
        // instrumenting, and that independence is the entire point of the channel.
        assertTrue(LabStream.GROUND_TRUTH in streams)
    }
}
