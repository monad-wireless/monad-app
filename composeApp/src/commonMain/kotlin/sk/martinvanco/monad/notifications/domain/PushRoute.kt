package sk.martinvanco.monad.notifications.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sk.martinvanco.monad.core.deeplink.DeepLink
import sk.martinvanco.monad.core.deeplink.DeepLinkParser

/**
 * Where a tapped push wants the app to go (IP-157).
 *
 * Two shapes, decided from the FCM data payload. `quest_id` wins over `deep_link` when both are
 * present, because a callout is an invitation to one quest and the link is at most a fallback the
 * desk added. The optional `notification_id` rides along so the row can be marked read on arrival.
 */
sealed interface PushRoute {
    val notificationId: String?

    /** A `quest_callout`: open the quest so the participant can press Start. */
    data class Quest(val questId: String, override val notificationId: String? = null) : PushRoute

    /** A `general` message whose `deep_link` is one of the two printed grammars (`/d/`, `/m/`). */
    data class Link(val link: DeepLink, override val notificationId: String? = null) : PushRoute

    companion object {
        const val KEY_QUEST_ID = "quest_id"
        const val KEY_DEEP_LINK = "deep_link"
        const val KEY_NOTIFICATION_ID = "notification_id"

        /** Null when the payload names nowhere to go, which is the normal case for a plain message. */
        fun from(data: Map<String, Any?>): PushRoute? {
            val notificationId = data[KEY_NOTIFICATION_ID]?.toString()?.ifBlank { null }
            val questId = data[KEY_QUEST_ID]?.toString()?.ifBlank { null }
            if (questId != null) return Quest(questId, notificationId)
            val link = DeepLinkParser.parse(data[KEY_DEEP_LINK]?.toString()) ?: return null
            return Link(link, notificationId)
        }
    }
}

/**
 * Parks a push route between the platform delivering it and the Navigator being ready.
 *
 * The same problem `PendingDeepLink` solves, with one difference that changes the mechanism: a
 * push tap can arrive while the app is already running and composed, so a take-once slot the UI
 * polls on first composition would miss it. A [StateFlow] covers both cases — a cold start sees
 * the value on first collection, a warm app sees the change — and the collector clears it after
 * navigating so it cannot fire twice.
 */
object PendingPushRoute {
    private val _route = MutableStateFlow<PushRoute?>(null)
    val route: StateFlow<PushRoute?> = _route.asStateFlow()

    fun park(route: PushRoute?) {
        if (route != null) _route.value = route
    }

    /** Parse then park. A payload without a destination is ignored. */
    fun parkPayload(data: Map<String, Any?>) = park(PushRoute.from(data))

    fun clear() {
        _route.value = null
    }
}
