package sk.martinvanco.monad.quests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The participant path's policy does not name transports.
 *
 * `QuestSessionCoordinator` holds the one rule on this path that cannot be recovered from if it is
 * wrong — **submit, upload, and only then purge** — and it used to hold it while naming six
 * concrete collaborators from four features' data layers: `UserRepository`, `QuestsService`,
 * `LabConfigService`, `LabSessionRepository`, `LabSessionUploader`,
 * `QuestStepCompletionRepository`. Alongside them `quests/domain` also held the wire DTOs
 * (`TaskDto`, `QuestDetailDto`, `ActiveTaskDto`, their configs and their parser), so the package
 * was simultaneously the policy, the JSON schema and the database vocabulary.
 *
 * Two costs, and the second is the one that bites. The rule could not be read without a database,
 * an HTTP client and a Ktor engine in scope. And the coordinator could reach `forceDelete`,
 * `deleteAll` and `purgeUploaded` — the methods whose entire purpose is to destroy the only copy of
 * a recording that cannot be made again.
 *
 * It now depends on the ports in `quests/domain/port`, implemented by adapters in
 * `quests/data/adapter`. A port is only worth having while nothing routes around it, and the way it
 * gets routed around is one convenient import added under time pressure — so this test reads the
 * source of `quests/domain` and fails on any import from a data layer.
 *
 * Source-text rather than reflection, for the same reason as [sk.martinvanco.monad.lab.LabBoundaryTest]:
 * the rule is about what the *code* may name, and an import is exactly that fact. It also covers
 * `commonMain` regardless of which target compiled it.
 *
 * **Scope.** `lab/domain` and `quests/domain` are held to this rule; `auth/domain` deliberately is
 * not. Those two are the packages whose correctness is a claim about data that cannot be
 * re-collected, and they are the two that reach *across features*. `AuthManager` names its own
 * feature's repository and service, which is an ordinary use-case object, and inverting it would
 * mean replacing the SQLDelight `User` type across the auth and my-account screens — a redesign of
 * the auth read model rather than a re-placement of a file.
 */
class QuestsBoundaryTest {

    @Test
    fun `quests domain never imports a data layer`() {
        val offenders = domainSources()
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { line ->
                        line.startsWith("import sk.martinvanco.monad.") &&
                            line.contains(".data.")
                    }
                    .map { "${file.name}: $it" }
            }

        if (offenders.isNotEmpty()) {
            fail(
                "quests/domain must not depend on any data layer — the coordinator names ports, " +
                    "not repositories, services or DTOs. Add a port in quests/domain/port and an " +
                    "adapter in quests/data/adapter instead:\n" +
                    offenders.joinToString("\n") { "  $it" }
            )
        }
    }

    /**
     * No wire types in the policy package.
     *
     * `@Serializable` means "the backend decided the shape of this". A policy expressed in the
     * backend's shapes changes whenever the backend does, and the DTOs that used to live here were
     * imported by fourteen presentation files — so `quests/domain` was, in practice, the app's JSON
     * schema with a rule hidden in it.
     */
    @Test
    fun `quests domain holds no wire types`() {
        val offenders = domainSources()
            .filter { it.readText().contains("@Serializable") }
            .map { it.name }

        if (offenders.isNotEmpty()) {
            fail(
                "quests/domain must not hold @Serializable types — DTOs belong in " +
                    "quests/data/dto:\n" + offenders.joinToString("\n") { "  $it" }
            )
        }
    }

    /**
     * The ports exist and the adapters implement them.
     *
     * Without this, the rule above could be satisfied by deleting the dependency rather than by
     * inverting it, and the next reader would not know a seam was ever intended.
     */
    @Test
    fun `the adapters implement the ports`() {
        val adapters = File(QUESTS_ROOT, "data/adapter/QuestAdapters.kt")
        assertTrue(adapters.isFile, "missing ${adapters.absolutePath}")
        val source = adapters.readText()

        listOf(
            "LabBundleSource",
            "LabSessionArchive",
            "ParticipantDirectory",
            "QuestCompletionGateway",
            "QuestStepJournal",
        ).forEach { port ->
            assertTrue(
                source.contains(") : $port {"),
                "no adapter implements $port in ${adapters.name}",
            )
            assertTrue(
                File(QUESTS_ROOT, "domain/port/QuestPorts.kt").readText()
                    .contains("interface $port {"),
                "port $port is not declared in quests/domain/port/QuestPorts.kt",
            )
        }
    }

    private fun domainSources(): List<File> {
        val root = File(QUESTS_ROOT, "domain")
        assertTrue(root.isDirectory, "quests/domain not found at ${root.absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        /** Android unit tests run with the module directory as the working directory. */
        val QUESTS_ROOT = File("src/commonMain/kotlin/sk/martinvanco/monad/quests")
    }
}
