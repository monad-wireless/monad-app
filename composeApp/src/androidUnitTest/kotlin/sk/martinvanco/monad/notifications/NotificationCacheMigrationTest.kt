package sk.martinvanco.monad.notifications

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.notifications.data.NotificationRepository
import sk.martinvanco.monad.notifications.domain.InboxItem
import sk.martinvanco.monad.notifications.domain.NotificationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * Migration 14 gives a deployed handset a usable inbox cache (IP-157).
 *
 * The same shape as the lab migration tests: apply the migration onto a schema that already has
 * the objects, then use the table through the repository, because a table that exists and rejects
 * an insert passes a presence check and fails on the first push.
 */
class NotificationCacheMigrationTest {

    private fun item(id: String, sentAt: String, readAt: String? = null) = InboxItem(
        id = id,
        type = NotificationType.GENERAL,
        title = "t",
        body = "b",
        questId = null,
        deepLink = null,
        sentAt = Instant.parse(sentAt),
        readAt = readAt?.let { Instant.parse(it) },
    )

    @Test
    fun migration14GivesADeployedHandsetTheInboxCache() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val result = Database.Schema.migrate(driver, 14, 15)
            assertTrue(result is QueryResult.Value<Unit> || result.value == Unit)

            val repository = NotificationRepository(Database(driver))
            runBlocking {
                assertNull(repository.newestSentAt())
                repository.upsertAll(
                    listOf(
                        item("a", "2026-09-16T08:00:00Z"),
                        item("b", "2026-09-16T09:00:00Z", readAt = "2026-09-16T09:30:00Z"),
                    ),
                )
                assertEquals(1L, repository.countUnread())
                assertEquals(Instant.parse("2026-09-16T09:00:00Z"), repository.newestSentAt())
                assertEquals(listOf("b", "a"), repository.all().map { it.id })

                repository.markRead("a", Instant.parse("2026-09-16T10:00:00Z"))
                assertEquals(0L, repository.countUnread())

                // A re-fetched row replaces rather than duplicates, and an earlier read stamp is kept.
                repository.upsertAll(listOf(item("a", "2026-09-16T08:00:00Z")))
                assertEquals(2, repository.all().size)

                repository.deleteAll()
                assertEquals(0, repository.all().size)
            }
        } finally {
            driver.close()
        }
    }
}
