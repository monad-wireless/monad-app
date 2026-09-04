package sk.martinvanco.monad.marker.presentation

import sk.martinvanco.monad.lab.domain.HandsetIdentity
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.quests.data.dto.ProbeConfig
import sk.martinvanco.monad.quests.data.dto.QuestDetailDto
import sk.martinvanco.monad.quests.data.dto.TaskConfigParser
import sk.martinvanco.monad.quests.data.dto.TaskType
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository

/**
 * What the screen is doing, and what a participant should be told while it does it.
 *
 * `resolving` and `starting` are separate states rather than one spinner because they fail for
 * different reasons and a participant can act on only one of them: nothing here accepts this card
 * (walk to a different one) versus you are not signed in (sign in).
 */
data class MarkerState(
    val code: String = "",
    val resolving: Boolean = true,
    val starting: Boolean = false,
    /** Set when a quest was started and the participant should be taken into it. */
    val startedQuestId: String? = null,
    /** Quests that accept this card, when more than one does and a choice is needed. */
    val candidates: List<QuestDetailDto> = emptyList(),
    val error: String? = null,
    val signInRequired: Boolean = false,
)

/**
 * Turn a scanned marker card into a running quest, in one participant action (IP-140).
 *
 * The whole point of claiming `/m/<CODE>` on the handset is that pointing a camera at a card should
 * cost one action. Before this the same scan cost three — open the app, find the quest, press
 * Start — and the placement record already predicted what that does to a cohort: *"Per zone
 * transition it costs six actions across two scanners. It will fail with a cohort."*
 *
 * The resolution is deliberately done against **probe targets** and nothing else:
 *
 * - a `scan_qr` step is *not* considered, because its `expected_value` is positional inside an
 *   ordered script. Auto-starting a twenty-step staircase because someone scanned its check-in card
 *   would enrol a participant in a session they did not choose.
 * - a probe target is a standing offer. A quest saying "any of these twenty surveyed points" is by
 *   construction happy to be entered at any of them, which is what makes it safe to start
 *   unprompted.
 *
 * When exactly one quest accepts the card it starts immediately. When several do the participant
 * chooses, because guessing would silently decide which experiment their walk belongs to.
 */
class MarkerScreenModel(
    private val questsService: QuestsService,
    private val userRepository: UserRepository,
    private val questStepCompletionRepository: QuestStepCompletionRepository,
    private val handsets: HandsetIdentity,
    private val code: String,
    private val scannedValue: String,
) : StateScreenModel<MarkerState>(MarkerState(code = code)) {

    init {
        resolve()
    }

    fun retry() = resolve()

    fun choose(questId: String) = start(questId)

    private fun resolve() {
        screenModelScope.launch {
            mutableState.value = MarkerState(code = code, resolving = true)

            val token = userRepository.getCurrentUser()?.token
            if (token == null) {
                // Not an error state with a retry button: the participant has to do something
                // specific, and saying "network error" would send them to the wrong fix.
                mutableState.value = mutableState.value.copy(
                    resolving = false,
                    signInRequired = true,
                )
                return@launch
            }

            val matches = runCatching { candidatesFor(token) }.getOrElse {
                mutableState.value = mutableState.value.copy(
                    resolving = false,
                    error = "Could not reach the server. Check your connection and try again.",
                )
                return@launch
            }

            when (matches.size) {
                0 -> mutableState.value = mutableState.value.copy(
                    resolving = false,
                    error = "Nothing running right now uses this card. " +
                        "Tell the operator which code you scanned: $code",
                )

                1 -> start(matches.first().id)

                else -> mutableState.value = mutableState.value.copy(
                    resolving = false,
                    candidates = matches,
                )
            }
        }
    }

    /**
     * Every active quest with a probe step whose targets accept the scanned string.
     *
     * The list call already filters by this handset's capabilities, so a quest needing hardware
     * this phone lacks never reaches the match — which matters here more than on a list screen: an
     * auto-started quest gives the participant no moment to notice that their phone cannot do it.
     */
    private suspend fun candidatesFor(token: String): List<QuestDetailDto> {
        val listed = questsService.getActiveQuests().quests
        val matched = mutableListOf<QuestDetailDto>()
        for (summary in listed) {
            val detail = runCatching {
                QuestDetailDto.fromResponse(questsService.getQuestDetail(summary.id, token))
            }.getOrNull() ?: continue
            if (detail.acceptsProbeScan(scannedValue)) matched += detail
        }
        return matched
    }

    private fun start(questId: String) {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(
                resolving = false,
                starting = true,
                error = null,
            )
            try {
                val user = userRepository.getCurrentUser() ?: throw IllegalStateException("not signed in")
                val token = user.token ?: throw IllegalStateException("no auth token")

                // IP-149 — which phone is walking this run; frozen on the enrollment server-side.
                val response = questsService.startQuest(questId, token, handsets.describe())
                val now = currentTimeMillis()
                val minOrder = response.quest.steps.minOfOrNull { it.order } ?: 0
                response.quest.steps.forEach { step ->
                    questStepCompletionRepository.insertStepCompletion(
                        backendId = step.stepCompletionId,
                        enrollmentId = response.enrollmentId,
                        questStepId = step.id,
                        stepOrder = step.order,
                        stepType = step.type,
                        stepName = step.name,
                        stepConfig = step.config?.toString(),
                        status = if (step.order == minOrder) "in_progress" else "pending",
                        createdAt = now,
                    )
                }
                userRepository.setActiveQuestId(user.id, questId, response.enrollmentId)

                mutableState.value = mutableState.value.copy(
                    starting = false,
                    startedQuestId = questId,
                )
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(
                    starting = false,
                    error = e.message ?: "Could not start the run.",
                )
            }
        }
    }
}

/**
 * True when any probe step in this quest would accept [scanned].
 *
 * An extension rather than a method on the DTO: the DTO is a wire shape, and "would this quest take
 * my scan" is a question about the probe rule, which lives in [ProbeConfig.match].
 */
fun QuestDetailDto.acceptsProbeScan(scanned: String): Boolean = tasks
    .asSequence()
    .filter { it.type == TaskType.PROBE }
    .mapNotNull { task ->
        runCatching { TaskConfigParser.parseConfig(TaskType.PROBE, task.config) as? ProbeConfig }
            .getOrNull()
    }
    .any { it.match(scanned) != null }
