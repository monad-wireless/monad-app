package sk.martinvanco.monad.lab.domain

/**
 * Android — deliberately a no-op, and it says so rather than pretending.
 *
 * Two reasons, and both are platform facts rather than omissions:
 *
 *  * **Advertising survives backgrounding here.** Android honours a service-UUID advertisement from a
 *    foreground service, which `LabSessionService` already runs, so the identity frame does not depend
 *    on the screen being on.
 *  * **There is no pose track to lose.** ARCore odometry is not implemented on this platform (see
 *    `PoseTracker.android.kt`), so nothing here pauses when the app leaves the foreground.
 *
 * Keeping the screen on also cannot be done from here even if it were wanted: `FLAG_KEEP_SCREEN_ON`
 * belongs to a window, and this object has no Activity. A wake lock is not a substitute — it keeps the
 * CPU alive, which the foreground service already does, and leaves the screen to lock anyway.
 *
 * When an ARCore tracker does land, this becomes real work: it will need the Activity's window, and
 * the honest reason it is not attempted now is that a half-measure here would report a guarantee the
 * platform is not giving.
 */
actual class ForegroundWake actual constructor() {

    actual val isHeld: Boolean get() = false

    actual fun hold(reason: String): String =
        "screen wake not applicable on Android — the foreground service keeps advertising alive " +
            "and there is no pose track to pause"

    actual fun release() = Unit
}
