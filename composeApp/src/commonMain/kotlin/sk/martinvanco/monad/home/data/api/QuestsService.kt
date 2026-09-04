package sk.martinvanco.monad.home.data.api

import io.ktor.client.call.body
import sk.martinvanco.monad.lab.domain.detectCapabilities
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import sk.martinvanco.monad.lab.domain.HandsetDescriptor
import sk.martinvanco.monad.quests.data.dto.QuestStartRequestDto
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.home.data.dto.QuestDetailResponseDto
import sk.martinvanco.monad.home.data.dto.QuestListResponseDto
import sk.martinvanco.monad.quests.data.dto.QuestCompleteRequestDto
import sk.martinvanco.monad.quests.data.dto.QuestCompleteResponseDto
import sk.martinvanco.monad.quests.data.dto.QuestStartResponseDto

class QuestsService(private val ktorClient: KtorClient) {

    /**
     * Active quests this device can actually run.
     *
     * The capability list is sent so the backend withholds quests needing hardware this handset
     * lacks. A quest offered to a device that cannot satisfy it does not fail loudly — it produces
     * a session that looks complete and is missing the measurement, which is worse.
     */
    suspend fun getActiveQuests(): QuestListResponseDto {
        val response = ktorClient.client.get("/api/quests") {
            parameter("capabilities", detectCapabilities().capabilities.sorted().joinToString(","))
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

    /**
     * The same quest, fetched with a token so the steps carry their `config`.
     *
     * The anonymous route above deliberately withholds config: a `scan_qr` step's `expected_value`
     * is the answer key to the people channel, and until 2026-08-14 it was served to the whole
     * internet. The backend now emits config only when a token populates `getUser()`, and the
     * running quest gets it from `POST /start` instead.
     *
     * IP-140 needs it in one more place. Resolving "which quest accepts the card I just scanned"
     * means reading probe targets *before* starting anything, so this variant exists — additive,
     * so no existing caller starts sending a token it did not send before.
     */
    suspend fun getQuestDetail(questId: String, token: String): QuestDetailResponseDto {
        val response = ktorClient.client.get("/api/quest/$questId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }
        return response.body<QuestDetailResponseDto>()
    }

    /**
     * Start a quest, telling the backend which phone is walking it (IP-149).
     *
     * The descriptor rides as `{"handset": {...}}` and the backend freezes it on the enrollment as
     * measurement provenance — the transmitter beside the receiver (`?device=`). Null sends no
     * body, which the backend treats as a build that predates the descriptor; it never refuses a
     * start over a missing description.
     */
    suspend fun startQuest(questId: String, token: String, handset: HandsetDescriptor? = null): QuestStartResponseDto {
        val response = ktorClient.client.post("/api/quest/$questId/start") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
            }
            if (handset != null) {
                contentType(ContentType.Application.Json)
                setBody(QuestStartRequestDto(handset = handset))
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
