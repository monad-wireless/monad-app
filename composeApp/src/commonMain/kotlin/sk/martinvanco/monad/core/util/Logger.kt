package sk.martinvanco.monad.core.util

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier

object Logger {

    /**
     * DebugAntilog, but with the tag always supplied.
     *
     * On iOS `DebugAntilog` derives a missing tag by walking `NSThread.callStackSymbols` — an
     * NSArray interop iteration plus a dyld symbol lookup **per log call**. Measured with Time
     * Profiler on the device (2026-08-19, 61 s attach during a walk): 983 ms of
     * `Kotlin_NSArrayAsKList_get` plus ~1 s of `dyld findClosestSymbol`, all under
     * `DebugAntilog.performTag` — the single largest self-inflicted CPU cost in the app, spent
     * producing a tag nobody reads. A non-null tag skips that path entirely; the messages already
     * carry their own `[lab]`-style prefixes.
     */
    private object TaggedAntilog : Antilog() {
        private val delegate = DebugAntilog()

        override fun performLog(
            priority: LogLevel,
            tag: String?,
            throwable: Throwable?,
            message: String?,
        ) {
            delegate.log(priority, tag ?: "monad", throwable, message)
        }
    }

    fun init() {
        Napier.base(TaggedAntilog)
    }

    // Convenience methods
    fun d(message: String, tag: String? = null, throwable: Throwable? = null) {
        Napier.d(message, throwable, tag)
    }

    fun i(message: String, tag: String? = null, throwable: Throwable? = null) {
        Napier.i(message, throwable, tag)
    }

    fun w(message: String, tag: String? = null, throwable: Throwable? = null) {
        Napier.w(message, throwable, tag)
    }

    fun e(message: String, tag: String? = null, throwable: Throwable? = null) {
        Napier.e(message, throwable, tag)
    }

    fun v(message: String, tag: String? = null, throwable: Throwable? = null) {
        Napier.v(message, throwable, tag)
    }
}
