package sk.martinvanco.monad.lab.domain

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parse is pure, so the whole thing is testable without a device.
 *
 * The environment maps below are the ones Xcode actually injects. The first is a plain Release
 * launch; the rest reproduce the combination that killed the 2026-09-04 walk.
 */
class BuildDiagnosticsTest {

    @Test
    fun `a clean process reports nothing`() {
        val diagnostics = BuildDiagnostics.from(mapOf("HOME" to "/var/mobile", "TMPDIR" to "/tmp"))
        assertTrue(diagnostics.isClean)
        assertEquals(emptyList(), diagnostics.active)
    }

    @Test
    fun `an empty environment is clean rather than unknown`() {
        assertTrue(BuildDiagnostics.from(emptyMap()).isClean)
    }

    @Test
    fun `the 2026-09-04 combination is reported in full`() {
        val diagnostics = BuildDiagnostics.from(
            mapOf(
                "DYLD_INSERT_LIBRARIES" to
                    "/usr/lib/libMainThreadChecker.dylib:/usr/lib/libBacktraceRecording.dylib:" +
                    "/usr/lib/libViewDebuggerSupport.dylib",
                "METAL_DEVICE_WRAPPER_TYPE" to "1",
                "MTL_CAPTURE_ENABLED" to "1",
            )
        )
        assertFalse(diagnostics.isClean)
        assertContains(diagnostics.active, "Main Thread Checker")
        assertContains(diagnostics.active, "Backtrace Recording")
        assertContains(diagnostics.active, "View Debugger")
        assertContains(diagnostics.active, "Metal API Validation")
        assertContains(diagnostics.active, "GPU Frame Capture")
        assertEquals(5, diagnostics.active.size)
    }

    @Test
    fun `several inserted libraries in one colon-separated value are all found`() {
        val diagnostics = BuildDiagnostics.from(
            mapOf("DYLD_INSERT_LIBRARIES" to "/a/libgmalloc.dylib:/b/libclang_rt.tsan_iossim_dynamic.dylib")
        )
        assertEquals(listOf("Guard Malloc", "Thread Sanitizer"), diagnostics.active)
    }

    @Test
    fun `the metal wrapper is read as a number, so 0 is off and 2 is on`() {
        assertTrue(BuildDiagnostics.from(mapOf("METAL_DEVICE_WRAPPER_TYPE" to "0")).isClean)
        assertEquals(
            listOf("Metal API Validation"),
            BuildDiagnostics.from(mapOf("METAL_DEVICE_WRAPPER_TYPE" to "2")).active,
        )
    }

    /**
     * A value the app cannot parse must not become a finding. Reporting "Metal API Validation" for
     * `METAL_DEVICE_WRAPPER_TYPE=maybe` would put a blocker in front of an operator over a string
     * nobody set deliberately.
     */
    @Test
    fun `an unparseable value is not a finding`() {
        assertTrue(BuildDiagnostics.from(mapOf("METAL_DEVICE_WRAPPER_TYPE" to "maybe")).isClean)
        assertTrue(BuildDiagnostics.from(mapOf("MTL_SHADER_VALIDATION" to "")).isClean)
    }

    @Test
    fun `malloc diagnostics are reported too`() {
        assertEquals(
            listOf("Malloc Scribble", "Malloc Stack Logging"),
            BuildDiagnostics.from(
                mapOf("MallocScribble" to "1", "MallocStackLogging" to "1")
            ).active,
        )
    }
}
