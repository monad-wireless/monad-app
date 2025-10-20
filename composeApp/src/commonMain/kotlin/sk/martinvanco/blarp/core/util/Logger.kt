package sk.martinvanco.blarp.core.util

import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier

object Logger {

    fun init() {
        // Initialize Napier with DebugAntilog (works for both platforms)
        Napier.base(DebugAntilog())
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
