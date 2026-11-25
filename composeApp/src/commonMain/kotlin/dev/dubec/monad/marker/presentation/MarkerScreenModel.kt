package dev.dubec.monad.marker.presentation

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import dev.dubec.monad.auth.data.repository.UserRepository
import dev.dubec.monad.home.data.api.QuestsService
import dev.dubec.monad.quests.data.dto.ProbeConfig
import dev.dubec.monad.quests.data.dto.QuestDetailDto
import dev.dubec.monad.quests.data.dto.TaskConfigParser
import dev.dubec.monad.quests.data.dto.TaskType

/**
 * What the screen is doing, and what a participant should be told while it does it.
 *
 * The failures are separate fields rather than one error string because a participant can act on
 * each in a different way: sign in, go back to the open quest, check the connection, or pick another
 * point.
 */
data class MarkerState(
    val resolving: Boolean = true,
    /** Exactly one quest uses this card: its page opens at once. */
    val singleQuestId: String? = null,
    /** Quests that use this card, when more than one does. */
    val candidates: List<QuestDetailDto> = emptyList(),
    /** A quest this phone is already enrolled in. */
    val openQuestId: String? = null,
    val unreachable: Boolean = false,
    val signInRequired: Boolean = false,
)

/**
 * Turn a scanned marker card into the quests that use it (IP-140).
 *
 * It opens a page and never starts a run (since 2026-11-25). Starting belongs to the quest page,
 * which shows what the quest asks for and reads the server's refusal. This model used to have its
 * own copy of the start, without either, and it overwrote the pointer to a quest that was already
 * open on the phone.
 *
 * The resolution is done against **probe targets** and nothing else: a `scan_qr` step's
 * `expected_value` is positional inside an ordered script, so it does not offer the quest to
 * somebody who only happens to stand at that card.
 */
class MarkerScreenModel(
    private val questsService: QuestsService,
    private val userRepository: UserRepository,
    private val scannedValue: String,
) : StateScreenModel<MarkerState>(MarkerState()) {

    init {
        resolve()
    }

    fun retry() = resolve()

    private fun resolve() {
        screenModelScope.launch {
            mutableState.value = MarkerState(resolving = true)

            val token = userRepository.getCurrentUser()?.token
            if (token == null) {
                // Not an error state with a retry button: the participant has to do something
                // specific, and saying "network error" would send them to the wrong fix.
                mutableState.value = MarkerState(resolving = false, signInRequired = true)
                return@launch
            }

            val open = runCatching { userRepository.getCurrentUserActiveQuestId() }.getOrNull()
            if (open != null) {
                mutableState.value = MarkerState(resolving = false, openQuestId = open)
                return@launch
            }

            val matches = runCatching { candidatesFor(token) }.getOrElse {
                mutableState.value = MarkerState(resolving = false, unreachable = true)
                return@launch
            }

            mutableState.value = MarkerState(
                resolving = false,
                singleQuestId = matches.singleOrNull()?.id,
                candidates = if (matches.size > 1) matches else emptyList(),
            )
        }
    }

    /**
     * Every active quest with a probe step whose targets accept the scanned string.
     *
     * The list call already filters by this handset's capabilities, so a quest needing hardware
     * this phone lacks never reaches the match.
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
