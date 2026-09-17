package sk.martinvanco.monad.notifications.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.NotificationRecord
import sk.martinvanco.monad.notifications.domain.InboxItem
import sk.martinvanco.monad.notifications.domain.NotificationType

/**
 * The inbox cache over `NotificationRecord` (migration 14).
 *
 * Rows are upserted by id, so a refresh that returns a row already held — the server's `read_at`
 * caught up with a read made on another device, say — replaces it rather than duplicating it.
 * Timestamps are stored as epoch milliseconds; the wire string is parsed once, in [NotificationInbox].
 */
class NotificationRepository(private val database: Database) {
    private val queries = database.notificationQueries

    suspend fun all(): List<InboxItem> = withContext(Dispatchers.IO) {
        queries.selectAll().executeAsList().map { it.toItem() }
    }

    suspend fun upsertAll(items: List<InboxItem>) = withContext(Dispatchers.IO) {
        database.transaction {
            items.forEach { item ->
                queries.upsert(
                    id = item.id,
                    type = item.type.wire.ifEmpty { TYPE_UNKNOWN },
                    title = item.title,
                    body = item.body,
                    questId = item.questId,
                    deepLink = item.deepLink,
                    sentAtMs = item.sentAt.toEpochMilliseconds(),
                    readAtMs = item.readAt?.toEpochMilliseconds(),
                )
            }
        }
    }

    /** Marks one row read at [readAt]; a row already read keeps its earlier stamp. */
    suspend fun markRead(id: String, readAt: Instant) = withContext(Dispatchers.IO) {
        queries.markRead(readAtMs = readAt.toEpochMilliseconds(), id = id)
    }

    suspend fun countUnread(): Long = withContext(Dispatchers.IO) {
        queries.countUnread().executeAsOne()
    }

    /** The newest `sent_at` held, or null on an empty cache — the value of `?after=`. */
    suspend fun newestSentAt(): Instant? = withContext(Dispatchers.IO) {
        queries.newestSentAtMs().executeAsOneOrNull()?.MAX?.let { Instant.fromEpochMilliseconds(it) }
    }

    /** Logout and account deletion: the inbox belongs to the account, not to the handset. */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        queries.deleteAll()
    }

    private fun NotificationRecord.toItem() = InboxItem(
        id = id,
        type = NotificationType.fromWire(type),
        title = title,
        body = body,
        questId = questId,
        deepLink = deepLink,
        sentAt = Instant.fromEpochMilliseconds(sentAtMs),
        readAt = readAtMs?.let { Instant.fromEpochMilliseconds(it) },
    )

    private companion object {
        /** Stored for a type this build cannot name, so the row survives and reads back as UNKNOWN. */
        const val TYPE_UNKNOWN = "unknown"
    }
}
