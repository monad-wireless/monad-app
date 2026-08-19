package sk.martinvanco.monad.lab.domain

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The clock path a walk uses, and the property that makes it worth having.
 *
 * ### Why this matters more than it looks
 *
 * Every timestamp a walk records — poses, mesh changes, waypoints, the broadcast markers — is on the
 * device's own monotonic clock, whose origin means nothing to anybody else. `clock.tsv` is the only thing
 * that maps it onto the Unix epoch the fleet's `csid` nodes are chrony-disciplined to, and until this
 * path existed a walk had no way to produce one: the collector exchange needs an access point, and the
 * fleet's AX210 cannot be one.
 *
 * So the chain is: pose or triangle → `mono_ns` → clock samples → Unix epoch → CSI capture window. This
 * file defends the middle link.
 *
 * ### Why this lives in androidUnitTest
 *
 * `commonTest` in this project is pure by rule — no coroutines, no database, no Compose — and the burst is
 * a suspend function. `runBlocking` is JVM-only, and adding `kotlinx-coroutines-test` to reach `runTest`
 * would put a scheduler-patching dependency into a source set whose whole value is that it needs nothing.
 * So it sits beside the other coroutine-using tests instead. Nothing here is timing-sensitive: the burst
 * spacing is zero and the clock is scripted, so there is no virtual time to control.
 *
 * ### The invariant being pinned
 *
 * The reference path and the collector path must reduce **identically**. Gate G4 is evaluated on the
 * history without knowing which transport filled it, so two estimators would be two rules — and they
 * would drift the first time either was touched. What legitimately differs is precision, which the
 * estimate's own `delayNanos` carries and the caller records.
 */
class ReferenceClockBurstTest {

    /**
     * A reference clock with a scripted set of exchanges.
     *
     * Offsets are expressed the way a real one behaves: a true offset plus a **one-sided** queueing delay
     * on some exchanges. One-sided is the whole reason the estimator keeps the minimum rather than the
     * mean — network delay can only ever add.
     */
    private class ScriptedClock(
        private val script: List<Result<ClockExchange>>,
    ) : ReferenceClock {
        override val source: String = "test/scripted"
        var calls: Int = 0
            private set

        override suspend fun exchange(): Result<ClockExchange> {
            val next = script.getOrNull(calls) ?: Result.failure(IllegalStateException("script exhausted"))
            calls++
            return next
        }
    }

    /** An exchange with a known true offset and a known symmetric round trip. */
    private fun exchange(
        t1: Long,
        trueOffsetNanos: Long,
        rttNanos: Long,
    ): ClockExchange {
        val half = rttNanos / 2
        return ClockExchange(
            t1Nanos = t1,
            t2Nanos = t1 + trueOffsetNanos + half,
            t3Nanos = t1 + trueOffsetNanos + half,
            t4Nanos = t1 + rttNanos,
        )
    }

    private fun service() = ClockSyncService(LabDatagramSocket())

    @Test
    fun theBurstKeepsTheLeastDelayedExchange() = runBlocking {
        // Three exchanges, same true offset, wildly different round trips. The 2 ms one is the only
        // uncontaminated measurement in the set and is the one that must survive — averaging would fold
        // in a queueing bias that only ever pushes the offset one way.
        val clock = ScriptedClock(
            listOf(
                Result.success(exchange(t1 = 1_000, trueOffsetNanos = 500_000, rttNanos = 40_000_000)),
                Result.success(exchange(t1 = 2_000, trueOffsetNanos = 500_000, rttNanos = 2_000_000)),
                Result.success(exchange(t1 = 3_000, trueOffsetNanos = 500_000, rttNanos = 18_000_000)),
            )
        )
        val estimate = service()
            .runReferenceBurst(clock, ClockSyncPolicy(burstSize = 3, burstSpacingMs = 0))
            .getOrNull()

        assertNotNull(estimate)
        assertEquals(2_000_000L, estimate.delayNanos)
        assertEquals(500_000L, estimate.offsetNanos)
        assertEquals(3, estimate.samples, "every attempt is reported, not just the surviving one")
    }

    @Test
    fun failedExchangesAreSkippedAndTheBurstStillReduces() = runBlocking {
        // The normal state on a lab network: most requests answer, some do not. A burst that gave up on
        // the first failure would produce no clock sample at all for a walk, which is the difference
        // between a joinable corpus and an unjoinable one.
        val clock = ScriptedClock(
            listOf(
                Result.failure(IllegalStateException("timeout")),
                Result.success(exchange(t1 = 2_000, trueOffsetNanos = -250_000, rttNanos = 6_000_000)),
                Result.failure(IllegalStateException("timeout")),
            )
        )
        val estimate = service()
            .runReferenceBurst(clock, ClockSyncPolicy(burstSize = 3, burstSpacingMs = 0))
            .getOrNull()

        assertNotNull(estimate)
        assertEquals(-250_000L, estimate.offsetNanos)
        assertEquals(3, clock.calls, "a failure must not abort the rest of the burst")
    }

    @Test
    fun aBurstThatNeverReachedTheClockFailsRatherThanReportingZero() = runBlocking {
        // The one outcome that must never be silent. A fabricated zero offset would put an invented
        // mapping into clock.tsv, gate G4 would pass on evidence that does not exist, and every position
        // in the walk would be confidently placed at the wrong instant.
        val service = service()
        val result = service.runReferenceBurst(
            ScriptedClock(List(4) { Result.failure(IllegalStateException("offline")) }),
            ClockSyncPolicy(burstSize = 4, burstSpacingMs = 0),
        )

        assertTrue(result.isFailure)
        assertEquals(0, service.estimate.value.samples, "no sample means UNSYNCED, not offset zero")
        assertNotNull(service.lastError.value)
        assertTrue(service.lastError.value.orEmpty().contains("test/scripted"))
    }

    @Test
    fun successiveBurstsAccumulateHistorySoTheSkewTermBecomesIdentifiable() = runBlocking {
        // Gate G4 fits `unix_ts_ns ≈ a·mono_ns + b` per recording session and needs **two** samples before
        // the slope exists at all. With one it degrades to offset-only and the fold is flagged — which is
        // why the instrument pulls its second burst forward instead of waiting a full resync period.
        val service = service()
        val policy = ClockSyncPolicy(burstSize = 1, burstSpacingMs = 0)

        service.runReferenceBurst(
            ScriptedClock(listOf(Result.success(exchange(1_000, 400_000, 4_000_000)))),
            policy,
        )
        assertEquals(1, service.history.value.size)

        // Two minutes later, and 400 µs further out — a skew the fit can now see.
        service.runReferenceBurst(
            ScriptedClock(
                listOf(Result.success(exchange(120_000_000_000, 800_000, 4_000_000)))
            ),
            policy,
        )
        assertEquals(2, service.history.value.size)
        assertTrue(
            service.estimate.value.skewPpm != 0.0,
            "two samples over 120 s must identify a slope, got ${service.estimate.value.skewPpm}",
        )
    }

    @Test
    fun aResetLeavesNothingForTheNextSessionToInherit() = runBlocking {
        // The pre-flight probe runs bursts before a session starts. G4 fits per recording session, so a
        // probe's samples left in the history would be fitted into the next session's line — points from
        // before it existed.
        val service = service()
        service.runReferenceBurst(
            ScriptedClock(listOf(Result.success(exchange(1_000, 400_000, 4_000_000)))),
            ClockSyncPolicy(burstSize = 1, burstSpacingMs = 0),
        )
        assertEquals(1, service.history.value.size)

        service.reset()
        assertEquals(0, service.history.value.size)
        assertEquals(ClockEstimate.UNSYNCED, service.estimate.value)
        assertNull(service.lastError.value)
    }
}
