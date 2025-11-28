package sk.martinvanco.monad.quests.presentation.quest_detail

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.launch
import sk.martinvanco.monad.home.data.api.QuestsService
import sk.martinvanco.monad.quests.domain.QuestDetailDto

class QuestDetailScreenModel(
    private val questsService: QuestsService,
    private val questId: String
) : StateScreenModel<QuestDetailState>(QuestDetailState(questId = questId)) {

    init {
        loadQuestDetail()
    }

    fun onEvent(event: QuestDetailEvent) {
        when (event) {
            is QuestDetailEvent.LoadQuest -> loadQuestDetail()
            is QuestDetailEvent.RetryLoad -> loadQuestDetail()
        }
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
}
