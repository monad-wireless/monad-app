package sk.martinvanco.monad.lab.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What happens to a walk's samples when the network is gone.
 *
 * The rule worth defending: a full buffer costs the OLDEST sample, never the newest, and every
 * eviction is counted. A courier that quietly dropped the newest sample would report a stale rate as
 * the current one — which is exactly the class of silent wrongness the whole telemetry path exists
 * to catch, reproduced inside the thing meant to catch it.
 */
class TelemetrySampleBufferTest {

    private fun sample(at: Long) = TelemetrySample(
        monoNs = at * 1_000_000,
        wallMs = at,
        elapsedMs = at,
        phase = "RUNNING",
        overall = "alive",
        recording = true,
        clockGate = "ok",
        streams = emptyList(),
    )

    @Test
    fun `drains in time order`() {
        val buffer = TelemetrySampleBuffer(10)
        (1L..3L).forEach { buffer.add(sample(it)) }

        val (samples, dropped) = buffer.drain()

        assertEquals(listOf(1L, 2L, 3L), samples.map { it.wallMs })
        assertEquals(0, dropped)
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `overflow evicts the oldest and keeps the newest`() {
        val buffer = TelemetrySampleBuffer(3)
        (1L..5L).forEach { buffer.add(sample(it)) }

        val (samples, dropped) = buffer.drain()

        assertEquals(listOf(3L, 4L, 5L), samples.map { it.wallMs })
        assertEquals(2, dropped, "the two evictions must be reported, not swallowed")
    }

    @Test
    fun `a drain resets the drop count so it is not double reported`() {
        val buffer = TelemetrySampleBuffer(2)
        (1L..5L).forEach { buffer.add(sample(it)) }

        assertEquals(3, buffer.drain().second)

        buffer.add(sample(6))
        assertEquals(0, buffer.drain().second)
    }

    @Test
    fun `a failed flush restores the batch ahead of what arrived meanwhile`() {
        val buffer = TelemetrySampleBuffer(10)
        (1L..3L).forEach { buffer.add(sample(it)) }

        val (inFlight, drops) = buffer.drain()
        // Heartbeats keep arriving during the request.
        buffer.add(sample(4))
        buffer.add(sample(5))

        buffer.restore(inFlight, drops)

        val (samples, dropped) = buffer.drain()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), samples.map { it.wallMs })
        assertEquals(0, dropped)
    }

    @Test
    fun `a restore that overflows the cap sheds the oldest and counts them`() {
        val buffer = TelemetrySampleBuffer(4)
        (1L..3L).forEach { buffer.add(sample(it)) }

        val (inFlight, drops) = buffer.drain()
        (4L..6L).forEach { buffer.add(sample(it)) }

        buffer.restore(inFlight, drops)

        val (samples, dropped) = buffer.drain()
        assertEquals(listOf(3L, 4L, 5L, 6L), samples.map { it.wallMs })
        assertEquals(2, dropped)
    }

    @Test
    fun `a restore carries the earlier drop count forward`() {
        val buffer = TelemetrySampleBuffer(2)
        (1L..4L).forEach { buffer.add(sample(it)) }

        val (inFlight, drops) = buffer.drain()
        assertEquals(2, drops)

        buffer.restore(inFlight, drops)

        assertEquals(2, buffer.drain().second, "a failed flush must not lose the gap it already knew about")
    }

    @Test
    fun `clear forgets both the samples and the drop count`() {
        val buffer = TelemetrySampleBuffer(2)
        (1L..5L).forEach { buffer.add(sample(it)) }

        buffer.clear()

        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.dropped)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `an empty buffer drains to nothing`() {
        val buffer = TelemetrySampleBuffer(5)

        val (samples, dropped) = buffer.drain()

        assertTrue(samples.isEmpty())
        assertEquals(0, dropped)
        assertTrue(buffer.isEmpty)
        assertFalse(buffer.size > 0)
    }

    @Test
    fun `ten minutes at one hertz fits exactly, and the next second costs the first`() {
        val buffer = TelemetrySampleBuffer(600)
        (1L..600L).forEach { buffer.add(sample(it)) }
        assertEquals(0, buffer.dropped)

        buffer.add(sample(601))

        val (samples, dropped) = buffer.drain()
        assertEquals(600, samples.size)
        assertEquals(2L, samples.first().wallMs)
        assertEquals(601L, samples.last().wallMs)
        assertEquals(1, dropped)
    }
}
