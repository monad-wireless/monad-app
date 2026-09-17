package sk.martinvanco.monad.notifications.domain

import kotlinx.datetime.Instant

/** The two notification types IP-157 defines, plus the row an older build cannot name. */
enum class NotificationType(val wire: String) {
    GENERAL("general"),
    QUEST_CALLOUT("quest_callout"),
    /** A type this build does not know. Shown as a plain message, never dropped. */
    UNKNOWN("");

    companion object {
        fun fromWire(value: String): NotificationType =
            entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * One inbox row, as the screens read it.
 *
 * Pure and platform-free so the grouping and the unread count can be checked without a database or
 * a clock. [sentAt] is an [Instant] rather than the wire string because "which day is this" is a
 * question about a moment and a time zone, and answering it on a string is how Today and Yesterday
 * drift around midnight.
 */
data class InboxItem(
    val id: String,
    val type: NotificationType,
    val title: String,
    val body: String,
    val questId: String?,
    val deepLink: String?,
    val sentAt: Instant,
    val readAt: Instant?,
) {
    val isUnread: Boolean get() = readAt == null

    /** A callout that names a quest opens that quest; a callout without one is a message. */
    val opensQuestId: String? get() = questId.takeIf { type == NotificationType.QUEST_CALLOUT && !it.isNullOrBlank() }
}
