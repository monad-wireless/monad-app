package sk.martinvanco.monad.lab.domain

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np
import platform.Foundation.NSProcessInfo
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `CLOCK_MONOTONIC_RAW` on Darwin: monotonic and **continues to advance while the device is
 * asleep**.
 *
 * The distinction is load-bearing and easy to get wrong. `CLOCK_UPTIME_RAW` — the one most sample
 * code reaches for — stops during sleep, which would silently compress a backgrounded session's
 * timeline exactly when the phone is in a pocket doing its job. `mach_absolute_time()` has the same
 * problem plus a timebase conversion. This is the only correct source here.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun monotonicNanos(): Long = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()).toLong()

@OptIn(ExperimentalUuidApi::class)
private val processEpochId: String by lazy {
    // systemUptime is seconds since boot; pairing it with wall time gives a boot instant stable
    // across the process lifetime, which is all the continuity epoch needs to identify.
    val bootInstantSeconds =
        (NSProcessInfo.processInfo.systemUptime).toLong()
    "ios-$bootInstantSeconds-${Uuid.random().toString().take(8)}"
}

actual fun clockBootId(): String = processEpochId

actual fun clockSourceName(): String = "darwin/CLOCK_MONOTONIC_RAW"
