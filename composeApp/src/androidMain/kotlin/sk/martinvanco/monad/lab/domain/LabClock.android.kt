package sk.martinvanco.monad.lab.domain

import android.os.SystemClock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `elapsedRealtimeNanos()` — monotonic **and** continues to advance while the device sleeps, which
 * is the property that matters: a field session is a backgrounded phone in a pocket, and a clock
 * that stops during sleep would silently compress the session's timeline. `nanoTime()` and
 * `uptimeMillis()` are both wrong here for that reason.
 */
actual fun monotonicNanos(): Long = SystemClock.elapsedRealtimeNanos()

@OptIn(ExperimentalUuidApi::class)
private val processEpochId: String by lazy {
    // Android exposes no boot identifier. Approximating with (boot instant, process id) is enough
    // for the only job this has: invalidating monotonic continuity across a restart, so analysis
    // never joins two epochs into one timeline.
    val bootInstantMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    "android-$bootInstantMillis-${Uuid.random().toString().take(8)}"
}

actual fun clockBootId(): String = processEpochId

actual fun clockSourceName(): String = "android/SystemClock.elapsedRealtimeNanos"
