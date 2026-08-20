package sk.martinvanco.monad.lab.domain.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pose stream must be able to report itself alive.
 *
 * Measured on two real walks, 2026-08-19. The instrument's heartbeat built its [StreamCounters]
 * without a `pose` value, so the monitor was fed zero on every tick while `pose.tsv` was being
 * written. Both sessions recorded 707 and 331 poses and their `health.tsv` says `state=dead
 * worst=dead` throughout, with `dead_ms` climbing past 108 seconds.
 *
 * That is the **inverse** of what this module exists for. It is built so a stream that has quietly
 * stopped cannot report itself healthy; a healthy stream reporting itself dead is the same defect
 * with the sign flipped, and worse in one way — the operator saw a red pose panel through two good
 * walks and so had no reason to trust a green one later.
 *
 * The wiring is a single argument inside `LabInstrument` and cannot be reached without a device, so
 * what is pinned here is that the counter is load-bearing and that fixing it did not make the stream
 * unable to fail.
 */
class PoseHealthCounterTest {

    private fun monitor(rateHz: Double = 5.0) = SessionHealthMonitor.forSession(
        emitting = false,
        commandedRateHz = 0.0,
        witnessing = false,
        resyncSeconds = 600,
        startedAtMillis = 0,
        tracking = true,
        poseRateHz = rateHz,
        clockDisciplined = true,
    )

    private fun poseOf(health: InstrumentHealth) =
        health.streams.single { it.stream == LabStream.POSE }

    @Test
    fun theCounterIsNotDiscarded() {
        // The defect in one line: a `pose` that never reaches `of(POSE)` is a stream watched against
        // a constant zero.
        assertEquals(707L, StreamCounters(pose = 707).of(LabStream.POSE))
        assertEquals(0L, StreamCounters().of(LabStream.POSE))
    }

    @Test
    fun aRisingPoseCountReportsAlive() {
        val monitor = monitor()
        var count = 0L
        var health = InstrumentHealth.IDLE
        for (second in 1..12) {
            count += 5
            health = monitor.tick(StreamCounters(pose = count), nowMillis = second * 1000L)
        }
        val pose = poseOf(health)
        assertEquals(StreamState.ALIVE, pose.state, "60 poses in 12 s at a commanded 5 Hz is on pace")
        assertEquals(StreamState.ALIVE, pose.worstState)
        assertEquals(0L, pose.millisDead)
    }

    @Test
    fun aFrozenPoseCountStillReportsDead() {
        // The other half of the guarantee. Fixing the wiring must not make the stream unable to fail:
        // odometry that stops is exactly what the commanded rate exists to catch.
        val monitor = monitor()
        var health = InstrumentHealth.IDLE
        for (second in 1..90) {
            health = monitor.tick(StreamCounters(pose = 0), nowMillis = second * 1000L)
        }
        val pose = poseOf(health)
        assertEquals(StreamState.DEAD, pose.state)
        assertTrue(pose.millisDead > 0)
    }

    @Test
    fun aPoseStreamThatCollapsesMidWalkIsCaught() {
        // The real target: not silence, but a track producing far below its commanded rate — the
        // odometry equivalent of the 11.6 %-delivery capture this package was written for.
        val monitor = monitor()
        var count = 0L
        var health = InstrumentHealth.IDLE
        for (second in 1..12) {
            count += 5
            health = monitor.tick(StreamCounters(pose = count), nowMillis = second * 1000L)
        }
        assertEquals(StreamState.ALIVE, poseOf(health).state)

        for (second in 13..30) {
            count += 1
            health = monitor.tick(StreamCounters(pose = count), nowMillis = second * 1000L)
        }
        val pose = poseOf(health)
        assertEquals(StreamState.DEGRADED, pose.state, "1 Hz against a commanded 5 Hz is degraded")
        assertTrue(pose.millisDegraded > 0)
    }

    @Test
    fun aWalkWatchesItsClockStream() {
        // Measured on the same two walks: `clock_gate_status = not_applicable` on every health row,
        // because applicability was gated on `emitting` and a walk does not emit. Both walks carried
        // two reference-clock samples, so the one check that says "this session can be placed on the
        // fleet timeline" was skipped for the only session shape that depends on it.
        val health = monitor().tick(StreamCounters(pose = 5, clock = 2), nowMillis = 1_000)
        assertTrue(
            health.streams.any { it.stream == LabStream.CLOCK },
            "a walk disciplines its clock, so the stream must be applicable: " +
                health.streams.map { it.stream },
        )
    }
}
