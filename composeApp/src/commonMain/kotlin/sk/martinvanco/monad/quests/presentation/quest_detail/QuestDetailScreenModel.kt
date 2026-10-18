package sk.martinvanco.monad.quests.presentation.quest_detail

import sk.martinvanco.monad.lab.domain.HandsetIdentity
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import sk.martinvanco.monad.auth.data.repository.UserRepository
import sk.martinvanco.monad.core.util.currentTimeMillis
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.quests.data.repository.QuestStepCompletionRepository
import sk.martinvanco.monad.quests.data.dto.QuestDetailDto

class QuestDetailScreenModel(
    private val questsService: QuestsService,
    private val userRepository: UserRepository,
    private val questStepCompletionRepository: QuestStepCompletionRepository,
    private val handsets: HandsetIdentity,
    private val questId: String
) : StateScreenModel<QuestDetailState>(QuestDetailState(questId = questId)) {

    init {
        loadQuestDetail()
    }

    fun onEvent(event: QuestDetailEvent) {
        when (event) {
            is QuestDetailEvent.LoadQuest -> loadQuestDetail()
            is QuestDetailEvent.RetryLoad -> loadQuestDetail()
            is QuestDetailEvent.StartQuest -> startQuest()
            is QuestDetailEvent.DismissStartQuestError -> dismissStartQuestError()
        }
    }

    private fun dismissStartQuestError() {
        mutableState.value = mutableState.value.copy(startQuestError = null)
    }

    private fun loadQuestDetail() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isLoading = true,
                error = null
            )
            try {
                val response = questsService.getQuestDetail(questId)
                val quest = QuestDetailDto.fromResponse(response)
                mutableState.value = mutableState.value.copy(
                    quest = quest,
                    isLoading = false,
                    error = null
                )
            } catch (e: ClientRequestException) {
                val errorMessage = when (e.response.status.value) {
                    401 -> "Please log in again."
                    404 -> "This quest no longer exists."
                    else -> "Could not load this quest."
                }
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = errorMessage
                )
            } catch (e: ServerResponseException) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = "The server has a problem. Try again in a minute."
                )
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = "No connection. Check your internet."
                )
            }
        }
    }

    /**
     * Why the server refused a start, in words a participant can act on.
     *
     * Every 409 used to read "Already enrolled in this quest", including a phone that lacks a
     * capability and a node that is resting. The server says which in `reason` (a closed set,
     * `App\Quest\QuestAvailability`), so the screen reads that instead of guessing from the status.
     */
    private fun startRefusal(status: Int, body: String): String {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val reason = json?.get("reason")?.jsonPrimitive?.contentOrNull
        val serverText = json?.get("error")?.jsonPrimitive?.contentOrNull
        return when {
            status == 401 -> "Please log in again."
            reason == "capability_missing" -> "This phone cannot run this quest. Update the app and try again."
            reason == "in_progress" -> "You already have this quest running. Open it from the home screen, or wait a few minutes."
            reason == "cooldown" -> "You ran this recently. Try again a bit later."
            reason == "node_idle" -> "The sensors are resting right now. Try again during a recording session."
            reason == "window_closed" -> "This quest is not open right now."
            reason == "device_inactive" -> "This sensor box is out of service."
            reason == "not_armed" -> "The sensors are not ready yet. Try again later."
            serverText != null -> "$serverText."
            else -> "Could not start the quest (error $status)."
        }
    }

    private fun startQuest() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isStartingQuest = true,
                startQuestError = null
            )

            try {
                val user = userRepository.getCurrentUser()
                    ?: throw Exception("User not logged in")

                val token = user.token
                    ?: throw Exception("No auth token")

                // Call API to start quest
                // IP-149 — which phone is walking this run; frozen on the enrollment server-side.
                val response = questsService.startQuest(questId, token, handsets.describe())

                // Store step completions locally
                val currentTime = currentTimeMillis()
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
                        createdAt = currentTime
                    )
                }

                // Update user's active quest and enrollment
                userRepository.setActiveQuestId(user.id, questId, response.enrollmentId)

                mutableState.value = mutableState.value.copy(
                    isStartingQuest = false,
                    enrollmentId = response.enrollmentId
                )

            } catch (e: ClientRequestException) {
                val body = runCatching { e.response.bodyAsText() }.getOrDefault("")
                mutableState.value = mutableState.value.copy(
                    isStartingQuest = false,
                    startQuestError = startRefusal(e.response.status.value, body)
                )
            } catch (e: ServerResponseException) {
                mutableState.value = mutableState.value.copy(
                    isStartingQuest = false,
                    startQuestError = "The server has a problem. Try again in a minute."
                )
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(
                    isStartingQuest = false,
                    startQuestError = "No connection. Check your internet and try again."
                )
            }
        }
    }
}
