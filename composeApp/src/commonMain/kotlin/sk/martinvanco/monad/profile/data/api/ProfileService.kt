package sk.martinvanco.monad.profile.data.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.profile.data.dto.ProfileStatsDto

class ProfileService(private val ktorClient: KtorClient) {

    /**
     * Aggregated from enrollments and step completions. Creates nothing and captures nothing:
     * every number is computed from rows the backend already wrote.
     *
     * The token is passed in and attached here, because [KtorClient] carries no auth
     * interceptor — every authenticated call in this app does the same thing by hand
     * (`QuestsService.startQuest`, `getQuestDetail`). Omitting it does not fail at compile
     * time and does not fail loudly at runtime either: the route answers
     * `401 {"code":401,"message":"JWT Token not found"}` and the screen shows its error card,
     * which reads as "the server is down" rather than "this request was anonymous".
     */
    suspend fun getStats(token: String): ProfileStatsDto =
        ktorClient.client.get("/api/me/stats") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }.body()
}
