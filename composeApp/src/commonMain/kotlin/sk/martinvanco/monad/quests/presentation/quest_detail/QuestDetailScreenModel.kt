package sk.martinvanco.monad.quests.presentation.quest_detail

import sk.martinvanco.monad.lab.domain.HandsetIdentity
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.launch
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
                    400 -> "Invalid quest ID format"
                    401 -> "Authentication required"
                    403 -> "Access denied"
                    404 -> "Quest not found"
                    else -> "Failed to load quest"
                }
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = errorMessage
                )
            } catch (e: ServerResponseException) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = "Server error. Please try again later."
                )
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = "Network error. Please check your connection."
                )
            }
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
                val errorMessage = when (e.response.status.value) {
                    400 -> "Cannot start quest"
                    401 -> "Authentication required"
                    409 -> "Already enrolled in this quest"
                    else -> "Failed to start quest"
                }
                mutableState.value = mutableState.value.copy(
                    isStartingQuest = false,
                    startQuestError = errorMessage
                )
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(
                    isStartingQuest = false,
                    startQuestError = e.message ?: "Failed to start quest"
                )
            }
        }
    }
}
