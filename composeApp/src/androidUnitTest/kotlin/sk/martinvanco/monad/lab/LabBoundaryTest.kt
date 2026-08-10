package sk.martinvanco.monad.lab

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The measurement path does not name storage.
 *
 * `LabInstrument` and `GroundTruthRecorder` are the two objects whose correctness is a *scientific*
 * claim, and both used to hold a SQLDelight repository directly. That made the start-up order, the
 * gate sequence and the sidecar assembly unreadable without a database in scope, and it left the
 * instrument holding methods — `purgeUploaded`, `forceDelete`, `markUploaded` — that the
 * measurement path must never be able to call. They now depend on [
 * sk.martinvanco.monad.lab.domain.SessionRecorder] and [
 * sk.martinvanco.monad.lab.domain.GroundTruthStore], two narrow ports the repositories implement.
 *
 * A port is only worth having while nothing routes around it, and the way it gets routed around is
 * one convenient import added under time pressure. This test is the guard: it reads the source of
 * `lab/domain` and fails on any import from `lab/data`.
 *
 * Source-text rather than reflection on purpose — the rule is about what the *code* may name, and
 * an import is exactly that fact. It also means the test covers `commonMain` regardless of which
 * target compiled it.
 */
class LabBoundaryTest {

    @Test
    fun `lab domain never imports lab data`() {
        val offenders = labDomainSources()
            .flatMap { file ->
                file.readLines()
                    .filter { it.trimStart().startsWith("import sk.martinvanco.monad.lab.data") }
                    .map { "${file.name}: ${it.trim()}" }
            }

        if (offenders.isNotEmpty()) {
            fail(
                "lab/domain must not depend on lab/data — the instrument names ports, not the " +
                    "database. Add a port beside SessionRecorder/GroundTruthStore instead:\n" +
                    offenders.joinToString("\n") { "  $it" }
            )
        }
    }

    /**
     * The ports exist and the repositories implement them.
     *
     * Without this, the rule above could be satisfied by deleting the dependency rather than by
     * inverting it, and the next reader would not know a seam was ever intended.
     */
    @Test
    fun `the repositories implement the ports`() {
        val repository = source("data/LabSessionRepository.kt")
        val groundTruth = source("data/GroundTruthRepository.kt")

        assertTrue(
            repository.contains(") : SessionRecorder {"),
            "LabSessionRepository must implement SessionRecorder",
        )
        assertTrue(
            groundTruth.contains(") : GroundTruthStore {"),
            "GroundTruthRepository must implement GroundTruthStore",
        )
    }

    private fun labDomainSources(): List<File> {
        val root = File(LAB_ROOT, "domain")
        assertTrue(root.isDirectory, "lab/domain not found at ${root.absolutePath} (cwd=${File("").absolutePath})")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun source(relative: String): String {
        val file = File(LAB_ROOT, relative)
        assertTrue(file.isFile, "missing ${file.absolutePath}")
        return file.readText()
    }

    private companion object {
        /** Android unit tests run with the module directory as the working directory. */
        val LAB_ROOT = File("src/commonMain/kotlin/sk/martinvanco/monad/lab")
    }
}
