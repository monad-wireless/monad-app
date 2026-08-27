package sk.martinvanco.monad.lab

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * One capture session owns the rear camera, and a screen inside a running walk is not it.
 *
 * THE MEASUREMENT THIS TEST EXISTS FOR. The pose tracker runs its own capture session for the whole
 * walk. A `QrScanner` is a second one on the same device: the two contend, the OS picks the loser,
 * and on iOS the loser is ARKit. The walk console hit this first — see the warning on
 * `PoseTrack.seenCard`, which is why the tracker decodes cards off its own frames at 2 Hz and the
 * console's scanner button is offered only while no pose is running.
 *
 * `ProbeStep` (IP-140) then reintroduced it for participants, and the 2026-08-27 survey walk
 * measured the cost: `pose=stale` two seconds into the step, `pose=dead@0.0Hz` at sixteen, then two
 * silences of 27 s and 47 s in which the handset shipped no telemetry at all — the main thread was
 * blocked tearing one capture session down while the other relocalised. The step froze exactly
 * where the first card was read, and nothing failed. Twice is a rule, not an accident.
 *
 * So the rule is written down here rather than in a comment somebody will not read: a file that can
 * see [sk.martinvanco.monad.lab.domain.LabInstrument] AND opens a `QrScanner` is claiming the camera
 * from something that may already hold it. Such a file must be named below, with a reason. The
 * allow-list is the point — it does not stop the pattern, it stops it being added silently.
 *
 * Source-text rather than reflection, for the same reason [LabBoundaryTest] uses it: the rule is
 * about what a file may *name*, and an import is exactly that fact.
 */
class CameraOwnershipTest {

    /**
     * Files permitted to open a standalone scanner while the lab instrument is in scope, and why.
     *
     * `ProbeStep` is here because it must still work on a platform whose tracker holds nothing —
     * Android has no pose tracker in this build — and it decides at runtime, on
     * `LabInstrument.posePreviewHandle()`, rather than on a platform string. `LabConsoleScreen` is
     * here because its scanner is offered only when `state.poseReport == null`, which is the same
     * test spelled in the console's own vocabulary.
     *
     * Both are checked further down. Being on this list is permission to have the import, not
     * permission to open the camera unconditionally.
     */
    private val allowed = mapOf(
        "quests/presentation/components/steps/ProbeStep.kt"
            to "gated on posePreviewHandle() — Android has no tracker to contend with",
        "lab/presentation/LabConsoleScreen.kt"
            to "gated on poseReport == null — offered only when no walk is tracking",
    )

    @Test
    fun `no new screen claims the camera from the tracker`() {
        val offenders = commonMainSources()
            .filter { file ->
                val text = file.readText()
                text.contains("import qrscanner.QrScanner") &&
                    text.contains("import sk.martinvanco.monad.lab.domain.LabInstrument")
            }
            .map { it.relativeToCommonMain() }
            .filterNot { it in allowed.keys }

        if (offenders.isNotEmpty()) {
            fail(
                "These files open a standalone QrScanner with the lab instrument in scope. While a " +
                    "walk is tracking, the pose tracker already owns the rear camera, and a second " +
                    "capture session kills the pose stream and blocks the main thread. Read the " +
                    "code from the tracker's own frames (LabInstrument.seenCard) instead, or add " +
                    "the file to `allowed` with the runtime test that makes it safe:\n" +
                    offenders.joinToString("\n") { "  $it" }
            )
        }
    }

    /**
     * The probe step has a tracker-frame path at all, and its scanner is the fallback rather than
     * the default.
     *
     * The first assertion is what was missing on 2026-08-27. The second stops the fix being undone
     * by moving the scanner back above the branch, which would compile and would freeze again.
     */
    @Test
    fun `the probe step reads cards from the tracker first`() {
        val text = source("quests/presentation/components/steps/ProbeStep.kt")

        assertTrue(
            text.contains("instrument.seenCard"),
            "ProbeStep must read the code from the tracker's own frames (LabInstrument.seenCard), " +
                "not only from a capture session of its own.",
        )
        assertTrue(
            text.contains("instrument.posePreviewHandle()"),
            "ProbeStep must decide on posePreviewHandle() — whether something already holds the " +
                "camera — rather than on a platform string.",
        )

        val branch = text.indexOf("if (trackerOwnsCamera)")
        val scanner = text.indexOf("QrScanner(")
        assertTrue(branch >= 0, "ProbeStep must branch on trackerOwnsCamera")
        assertTrue(
            scanner > branch,
            "ProbeStep's QrScanner must sit in the fallback branch, after the trackerOwnsCamera " +
                "test. A scanner opened before that test is opened while the tracker holds the " +
                "camera, which is the fault this test exists to catch.",
        )
    }

    /**
     * The console's guard is still spelled the way the allow-list claims it is.
     *
     * Without this, the entry above would keep vouching for a file that had quietly stopped
     * checking.
     */
    @Test
    fun `the lab console offers its scanner only with no pose`() {
        val text = source("lab/presentation/LabConsoleScreen.kt")

        assertTrue(
            text.contains("state.poseReport == null"),
            "LabConsoleScreen must keep offering its scanner only when no pose is running.",
        )
    }

    private fun commonMainSources(): List<File> {
        assertTrue(
            COMMON_MAIN.isDirectory,
            "commonMain not found at ${COMMON_MAIN.absolutePath} (cwd=${File("").absolutePath})",
        )
        return COMMON_MAIN.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun File.relativeToCommonMain(): String =
        relativeTo(COMMON_MAIN).path.replace(File.separatorChar, '/')

    private fun source(relative: String): String {
        val file = File(COMMON_MAIN, relative)
        assertTrue(file.isFile, "missing ${file.absolutePath}")
        return file.readText()
    }

    private companion object {
        /** Android unit tests run with the module directory as the working directory. */
        val COMMON_MAIN = File("src/commonMain/kotlin/sk/martinvanco/monad")
    }
}
