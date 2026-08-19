package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import sk.martinvanco.monad.core.util.currentTimeMillis

/**
 * Disciplines the device clock against the collector.
 *
 * Runs a burst of four-timestamp exchanges over the **same socket the data stream uses**, so the
 * offset is measured over exactly the path the data takes, then reduces the burst by keeping the
 * minimum-delay sample. Repeating the burst at intervals gives skew.
 *
 * The offset is never applied to the timestamps the device records. It is stored, carried in the
 * session sidecar, and applied by the analysis — which keeps the correction auditable and lets a
 * later, better estimate be substituted without the raw data having been mutated.
 */
class ClockSyncService(
    private val socket: LabDatagramSocket,
) {

    private val _estimate = MutableStateFlow(ClockEstimate.UNSYNCED)
    val estimate: StateFlow<ClockEstimate> = _estimate.asStateFlow()

    private val _history = MutableStateFlow<List<ClockEstimate>>(emptyList())
    val history: StateFlow<List<ClockEstimate>> = _history.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Run one burst against an already-open socket. Returns the reduced estimate, or a failure if
     * every exchange in the burst timed out (which is itself diagnostic: it usually means the
     * socket is pinned to an interface that cannot reach the collector).
     */
    suspend fun runBurst(
        sessionId: ByteArray,
        policy: ClockSyncPolicy = ClockSyncPolicy(),
    ): Result<ClockEstimate> = withContext(Dispatchers.IO) {
        val exchanges = mutableListOf<ClockExchange>()

        repeat(policy.burstSize) { i ->
            val t1 = monotonicNanos()
            val request = LabPacket.encode(
                type = LabPacket.TYPE_TIME_REQUEST,
                sessionId = sessionId,
                sequence = i,
                monotonicNanos = t1,
                wallMillis = currentTimeMillis(),
            )

            val sendResult = socket.send(request)
            if (sendResult.isFailure) {
                _lastError.value = "send: ${sendResult.exceptionOrNull()?.message}"
                return@repeat
            }

            val received = socket.receive(policy.timeoutMs).getOrNull()
            val t4 = monotonicNanos()
            if (received == null) return@repeat

            val decoded = LabPacket.decode(received) ?: return@repeat
            val stamps = LabPacket.timeStamps(decoded) ?: return@repeat
            // Match the reply to our request; a stale reply from an earlier burst would bias the
            // minimum-delay filter in exactly the wrong direction (it looks fast).
            if (decoded.sequence != i) return@repeat

            exchanges += ClockExchange(t1Nanos = t1, t2Nanos = stamps.first, t3Nanos = stamps.second, t4Nanos = t4)
            if (policy.burstSpacingMs > 0) delay(policy.burstSpacingMs)
        }

        val previous = _estimate.value.takeIf { it.samples > 0 }
        val reduced = ClockEstimator.fromBurst(exchanges, previous)
            ?: return@withContext Result.failure(
                IllegalStateException("clock burst produced no usable exchange (${policy.burstSize} attempted)")
            )

        _estimate.value = reduced
        _history.value = (_history.value + reduced).takeLast(MAX_HISTORY)
        _lastError.value = null
        Napier.i(
            "[lab] clock offset=${reduced.offsetMillis} ms delay=${reduced.delayMillis} ms " +
                "skew=${reduced.skewPpm} ppm from ${reduced.samples} exchanges"
        )
        Result.success(reduced)
    }

    /**
     * Run a burst against a [ReferenceClock] instead of the collector socket.
     *
     * Reduced by the **same** minimum-delay filter and folded into the **same** history, so gate G4 is
     * evaluated identically whichever path produced the samples. That identity is the point: a walk and
     * an illuminator session must be judged by one rule, and a second estimator would drift from the
     * first the moment either was touched.
     *
     * What differs is only the precision, which the caller records — see [ReferenceClock] for what HTTP
     * is honestly worth against G4a and G4b.
     */
    suspend fun runReferenceBurst(
        clock: ReferenceClock,
        policy: ClockSyncPolicy = ClockSyncPolicy(),
    ): Result<ClockEstimate> = withContext(Dispatchers.IO) {
        val exchanges = mutableListOf<ClockExchange>()
        repeat(policy.burstSize) { index ->
            clock.exchange()
                .onSuccess { exchanges += it }
                .onFailure { _lastError.value = "${clock.source}: ${it.message}" }
            // Spacing between exchanges, not before the first: an HTTP burst is already slow enough that
            // a leading delay would be felt at session start for nothing.
            if (policy.burstSpacingMs > 0 && index < policy.burstSize - 1) delay(policy.burstSpacingMs)
        }

        val previous = _estimate.value.takeIf { it.samples > 0 }
        val reduced = ClockEstimator.fromBurst(exchanges, previous)
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "${clock.source} burst produced no usable exchange (${policy.burstSize} attempted)"
                )
            )

        _estimate.value = reduced
        _history.value = (_history.value + reduced).takeLast(MAX_HISTORY)
        _lastError.value = null
        Napier.i(
            "[lab] ${clock.source} offset=${reduced.offsetMillis} ms delay=${reduced.delayMillis} ms " +
                "skew=${reduced.skewPpm} ppm from ${reduced.samples} exchanges"
        )
        Result.success(reduced)
    }

    /**
     * Fold a collector acknowledgement of a *data* packet into the estimate. The paced stream is
     * itself a stream of `t1` stamps, so when the collector echoes one back the exchange is free —
     * this is how drift stays observable between bursts without extra traffic.
     */
    fun observeDataEcho(t1Nanos: Long, t2Nanos: Long, t3Nanos: Long, t4Nanos: Long) {
        val exchange = ClockExchange(t1Nanos, t2Nanos, t3Nanos, t4Nanos)
        val current = _estimate.value
        // Only accept an echo that beats the standing delay — same minimum-delay filter, applied
        // continuously instead of per burst.
        if (current.samples == 0 || exchange.delayNanos < current.delayNanos) {
            ClockEstimator.fromBurst(listOf(exchange), current.takeIf { it.samples > 0 })?.let {
                _estimate.value = it.copy(samples = current.samples + 1)
            }
        }
    }

    fun reset() {
        _estimate.value = ClockEstimate.UNSYNCED
        _history.value = emptyList()
        _lastError.value = null
    }

    private companion object {
        const val MAX_HISTORY = 32
    }
}
