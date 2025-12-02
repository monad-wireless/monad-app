package sk.martinvanco.monad.home.data.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.home.data.dto.QuestDetailResponseDto
import sk.martinvanco.monad.home.data.dto.QuestListResponseDto
import sk.martinvanco.monad.quests.data.dto.QuestCompleteRequestDto
import sk.martinvanco.monad.quests.data.dto.QuestCompleteResponseDto
import sk.martinvanco.monad.quests.data.dto.QuestStartResponseDto

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

    suspend fun startQuest(questId: String, token: String): QuestStartResponseDto {
        val response = ktorClient.client.post("/api/quest/$questId/start") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        return response.body<QuestStartResponseDto>()
    }

    suspend fun completeQuest(
        questId: String,
        request: QuestCompleteRequestDto,
        token: String
    ): QuestCompleteResponseDto {
        val response = ktorClient.client.post("/api/quest/$questId/complete") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
            }
            setBody(request)
        }
        return response.body<QuestCompleteResponseDto>()
    }
}
