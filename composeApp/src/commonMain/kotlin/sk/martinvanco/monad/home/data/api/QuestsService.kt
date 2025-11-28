package sk.martinvanco.monad.home.data.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.home.data.dto.QuestDetailResponseDto
import sk.martinvanco.monad.home.data.dto.QuestListResponseDto

class QuestsService(private val ktorClient: KtorClient) {

    suspend fun getActiveQuests(): QuestListResponseDto {
        val response = ktorClient.client.get("/api/quests") {
            parameter("status", "active")
        }
        return response.body<QuestListResponseDto>()
    }

    suspend fun getExpiredQuests(): QuestListResponseDto {
        val response = ktorClient.client.get("/api/quests") {
            parameter("status", "expired")
        }
        return response.body<QuestListResponseDto>()
    }

    suspend fun getQuestDetail(questId: String): QuestDetailResponseDto {
        val response = ktorClient.client.get("/api/quest/$questId")
        return response.body<QuestDetailResponseDto>()
    }
}
