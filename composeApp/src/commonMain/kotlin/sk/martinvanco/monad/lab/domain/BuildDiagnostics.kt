package sk.martinvanco.monad.lab.domain

/**
 * Xcode's debug instrumentation, detected at run time.
 *
 * ### Why this exists
 *
 * On 2026-09-04 a 14 m 42 s survey walk was lost. The process was killed by FrontBoard with
 * `0x8BADF00D` — "Failed to terminate gracefully after 5.0s" — while the app was in the background.
 * It was not memory (thermal level 0, no jetsam) and it was not CPU (the main thread was blocked,
 * not spinning). The faulting stack was:
 *
 * ```
 * __ulock_wait2  <- blocked on an ObjC side-table os_unfair_lock
 * objc_object::sidetable_retain(bool)
 * _replacement_NSObject_conformsToProtocol_Instance_Version   <- libMainThreadChecker.dylib
 * -[MTLDebugCommandBuffer addPurgeableObject:]                <- Metal API Validation
 * -[CaptureMTLRenderCommandEncoder drawIndexedPrimitives:...] <- GPU frame capture
 * GrOpFlushState::drawMesh                                    <- Skia
 * androidx.compose.ui.window.MetalRedrawer.draw
 * ```
 *
 * Every Compose frame went through three interception layers, each of which retains ObjC objects on
 * the main thread. Kotlin/Native's ObjC interop already puts heavy weak-reference traffic on the
 * same side tables, so the lock is contended by construction. Add the shims and a single
 * `drawIndexedPrimitives` can outlast the 5 s the OS gives an app to quiesce.
 *
 * The session's data was never uploaded, because a killed process never reaches `stop()`.
 *
 * ### Why environment variables rather than the loaded-image list
 *
 * These variables are what Xcode injects to turn each diagnostic on, so reading them asks the same
 * question the crash report answers, one launch earlier. Walking `_dyld_image_count` would be the
 * other way and needs a cinterop the app does not link.
 *
 * The parse is a pure function over a map so it can be tested without a device — the same reason
 * `ClockEstimator` takes exchanges rather than a socket.
 */
data class BuildDiagnostics(
    /** Human-readable names of the diagnostics that are switched on, in a stable order. */
    val active: List<String>,
) {
    val isClean: Boolean get() = active.isEmpty()

    companion object {
        val NONE: BuildDiagnostics = BuildDiagnostics(emptyList())

        /**
         * The variables Xcode sets, and what each one costs a Metal-drawing app.
         *
         * `DYLD_INSERT_LIBRARIES` is matched by substring because it is a colon-separated list and
         * more than one checker can be inserted at once.
         */
        fun from(environment: Map<String, String>): BuildDiagnostics {
            val active = mutableListOf<String>()

            val inserted = environment["DYLD_INSERT_LIBRARIES"].orEmpty()
            if (inserted.contains("libMainThreadChecker")) active += "Main Thread Checker"
            if (inserted.contains("libgmalloc")) active += "Guard Malloc"
            if (inserted.contains("libclang_rt.tsan")) active += "Thread Sanitizer"
            if (inserted.contains("libclang_rt.asan")) active += "Address Sanitizer"
            if (inserted.contains("libBacktraceRecording")) active += "Backtrace Recording"
            if (inserted.contains("libViewDebuggerSupport")) active += "View Debugger"

            // Metal API Validation. The wrapper type is what puts MTLDebugCommandBuffer in front of
            // every command buffer, which is the frame in the 2026-09-04 stack.
            if (environment["METAL_DEVICE_WRAPPER_TYPE"]?.toIntOrNull()?.let { it > 0 } == true) {
                active += "Metal API Validation"
            }
            // GPU frame capture. Puts CaptureMTLRenderCommandEncoder in front of every encoder.
            if (environment["MTL_CAPTURE_ENABLED"] == "1") active += "GPU Frame Capture"
            if (environment["MTL_DEBUG_LAYER"] == "1") active += "Metal Debug Layer"
            if (environment["MTL_SHADER_VALIDATION"]?.toIntOrNull()?.let { it > 0 } == true) {
                active += "Metal Shader Validation"
            }
            if (environment["MallocScribble"] == "1") active += "Malloc Scribble"
            if (environment["MallocStackLogging"] == "1") active += "Malloc Stack Logging"

            return BuildDiagnostics(active)
        }
    }
}

/**
 * Read this process's diagnostics. iOS answers from its environment; Android has no equivalent set
 * of shims and returns [BuildDiagnostics.NONE] rather than inventing one.
 */
expect fun detectBuildDiagnostics(): BuildDiagnostics
