package sk.martinvanco.monad.lab.domain

import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS — `UIApplication.idleTimerDisabled`.
 *
 * Set on the main thread, always. `UIApplication` is a UIKit object and touching it from a background
 * queue is undefined behaviour that mostly appears to work, which is the worst kind: it would fail
 * intermittently, on some devices, mid-session.
 *
 * The flag is process-wide and not reference-counted by iOS, so this class owns it exclusively: a
 * second holder would clear it on its own release and take the screen down under the session still
 * running. Hence [isHeld] and the idempotent [hold].
 */
@OptIn(ExperimentalForeignApi::class)
actual class ForegroundWake actual constructor() {

    private var held = false

    actual val isHeld: Boolean get() = held

    actual fun hold(reason: String): String {
        if (held) return "screen already held awake"
        held = true
        onMain { UIApplication.sharedApplication.idleTimerDisabled = true }
        Napier.i("[lab] screen held awake: $reason")
        return "screen held awake — auto-lock would take the identity frame off air and pause ARKit"
    }

    actual fun release() {
        if (!held) return
        held = false
        onMain { UIApplication.sharedApplication.idleTimerDisabled = false }
        Napier.i("[lab] screen released")
    }

    /**
     * Run on the main thread, without suspending.
     *
     * Called from `stop()`'s teardown, which must not be able to block: a phone left awake because a
     * dispatch never returned is a flat battery, and a teardown that waited on the main queue while
     * the main queue waited on it is a deadlock at the end of every session.
     */
    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread()) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) { block() }
        }
    }
}
