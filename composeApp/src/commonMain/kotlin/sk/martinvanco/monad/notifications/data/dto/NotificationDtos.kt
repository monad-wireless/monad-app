package sk.martinvanco.monad.notifications.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of `GET /api/me/notifications` (IP-157).
 *
 * Field names are the proposal's, snake_case, and they are the backend's to change: this type is
 * the wire shape and nothing else reads it. [type] is `general` or `quest_callout`; anything else
 * is kept as text and shown as a plain message rather than dropped, because a new type on the
 * server must not make the inbox lose rows on an older build.
 *
 * Timestamps are ISO-8601 strings with an offset, as Symfony's serializer writes them. They are
 * parsed once, in the mapper to the domain item, so a malformed one costs one row and not the list.
 */
@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    @SerialName("quest_id") val questId: String? = null,
    @SerialName("deep_link") val deepLink: String? = null,
    @SerialName("sent_at") val sentAt: String,
    @SerialName("read_at") val readAt: String? = null,
)

/** Body of `PUT /api/me/push-token`. `platform` is `ios` or `android`. */
@Serializable
data class PushTokenRequestDto(
    val token: String,
    val platform: String,
    @SerialName("handset_id") val handsetId: String? = null,
)

/** Body and response of `GET` / `PUT /api/me/notification-preferences`. */
@Serializable
data class NotificationPreferencesDto(
    @SerialName("notify_general") val notifyGeneral: Boolean,
    @SerialName("notify_callouts") val notifyCallouts: Boolean,
)
