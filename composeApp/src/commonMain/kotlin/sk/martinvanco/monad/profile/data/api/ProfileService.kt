package sk.martinvanco.monad.profile.data.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.profile.data.dto.ProfileStatsDto

class ProfileService(private val ktorClient: KtorClient) {

    /**
     * Aggregated from enrollments and step completions. Creates nothing and captures nothing:
     * every number is computed from rows the backend already wrote.
     */
    suspend fun getStats(): ProfileStatsDto =
        ktorClient.client.get("/api/me/stats").body()
}
