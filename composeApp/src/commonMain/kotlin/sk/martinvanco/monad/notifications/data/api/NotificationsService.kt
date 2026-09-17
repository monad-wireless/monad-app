package sk.martinvanco.monad.notifications.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import sk.martinvanco.monad.core.data.remote.KtorClient
import sk.martinvanco.monad.notifications.data.dto.NotificationDto
import sk.martinvanco.monad.notifications.data.dto.NotificationPreferencesDto
import sk.martinvanco.monad.notifications.data.dto.PushTokenRequestDto
import sk.martinvanco.monad.notifications.domain.PushTokenGateway

/**
 * The notification endpoints of IP-157, all under `/api/me/` and all bearer-authenticated.
 *
 * The token is passed in and attached here, as every authenticated call in this app does by hand
 * (`ProfileService.getStats`, `QuestsService.startQuest`): [KtorClient] carries no auth
 * interceptor, and forgetting the header fails as a 401 the screen shows as "server down".
 */
class NotificationsService(private val ktorClient: KtorClient) : PushTokenGateway {

    /**
     * The inbox, newest first as the server orders it.
     *
     * [after] is the ISO-8601 `sent_at` of the newest row the device already holds; the server
     * returns rows sent strictly after it. Null asks for everything, which is what a fresh login
     * wants and what an empty cache needs.
     */
    suspend fun list(token: String, after: String? = null): List<NotificationDto> =
        ktorClient.client.get("/api/me/notifications") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            if (after != null) parameter("after", after)
        }.body()

    /** Idempotent on the server: reading a read notification is not an error. */
    suspend fun markRead(token: String, notificationId: String) {
        ktorClient.client.post("/api/me/notifications/$notificationId/read") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }
    }

    override suspend fun register(authToken: String, pushToken: String, platform: String, handsetId: String?) {
        ktorClient.client.put("/api/me/push-token") {
            headers { append(HttpHeaders.Authorization, "Bearer $authToken") }
            setBody(PushTokenRequestDto(token = pushToken, platform = platform, handsetId = handsetId))
        }
    }

    override suspend fun unregister(authToken: String, pushToken: String) {
        ktorClient.client.delete("/api/me/push-token/$pushToken") {
            headers { append(HttpHeaders.Authorization, "Bearer $authToken") }
        }
    }

    suspend fun getPreferences(token: String): NotificationPreferencesDto =
        ktorClient.client.get("/api/me/notification-preferences") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }.body()

    suspend fun putPreferences(token: String, preferences: NotificationPreferencesDto): NotificationPreferencesDto =
        ktorClient.client.put("/api/me/notification-preferences") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            setBody(preferences)
        }.body()
}
