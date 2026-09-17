package sk.martinvanco.monad.notifications

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import sk.martinvanco.monad.notifications.domain.InboxGrouping
import sk.martinvanco.monad.notifications.domain.InboxItem
import sk.martinvanco.monad.notifications.domain.NotificationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * The inbox's shape on screen (IP-157): days, labels, order, and the number on the badge.
 *
 * Pure on purpose. The boundary cases — a message sent at 23:59 in the server's zone that is
 * already tomorrow on the phone, a list the server returned out of order — are the ones a student
 * would notice and nobody would report.
 */
class InboxGroupingTest {

    private val bratislava = TimeZone.of("Europe/Bratislava")
    private val today = LocalDate(2026, 9, 16)

    private fun item(
        id: String,
        sentAt: String,
        readAt: String? = null,
        type: NotificationType = NotificationType.GENERAL,
        questId: String? = null,
    ) = InboxItem(
        id = id,
        type = type,
        title = "t$id",
        body = "b$id",
        questId = questId,
        deepLink = null,
        sentAt = Instant.parse(sentAt),
        readAt = readAt?.let { Instant.parse(it) },
    )

    @Test
    fun todayYesterdayThenDates() {
        val sections = InboxGrouping.groupByDay(
            listOf(
                item("a", "2026-09-16T08:00:00+02:00"),
                item("b", "2026-09-15T21:00:00+02:00"),
                item("c", "2026-09-01T12:00:00+02:00"),
            ),
            today = today,
            zone = bratislava,
        )
        assertEquals(listOf("Today", "Yesterday", "1 Sep 2026"), sections.map { it.label })
    }

    @Test
    fun newestFirstWithinAndAcrossDays() {
        val sections = InboxGrouping.groupByDay(
            listOf(
                item("old", "2026-09-16T07:00:00+02:00"),
                item("older", "2026-09-14T07:00:00+02:00"),
                item("new", "2026-09-16T09:00:00+02:00"),
            ),
            today = today,
            zone = bratislava,
        )
        assertEquals(listOf("new", "old"), sections[0].items.map { it.id })
        assertEquals(listOf("older"), sections[1].items.map { it.id })
        assertEquals(LocalDate(2026, 9, 16), sections[0].day)
    }

    @Test
    fun theDayIsDecidedInThePhonesZoneNotInUtc() {
        // 23:30 UTC on the 15th is 01:30 on the 16th in Bratislava: Today, not Yesterday.
        val sections = InboxGrouping.groupByDay(
            listOf(item("late", "2026-09-15T23:30:00Z")),
            today = today,
            zone = bratislava,
        )
        assertEquals("Today", sections.single().label)

        val utc = InboxGrouping.groupByDay(
            listOf(item("late", "2026-09-15T23:30:00Z")),
            today = today,
            zone = TimeZone.UTC,
        )
        assertEquals("Yesterday", utc.single().label)
    }

    @Test
    fun yesterdayCrossesAMonthAndAYear() {
        assertEquals("Yesterday", InboxGrouping.label(LocalDate(2026, 8, 31), LocalDate(2026, 9, 1)))
        assertEquals("Yesterday", InboxGrouping.label(LocalDate(2025, 12, 31), LocalDate(2026, 1, 1)))
        assertEquals("30 Dec 2025", InboxGrouping.label(LocalDate(2025, 12, 30), LocalDate(2026, 1, 1)))
    }

    @Test
    fun unreadCountsRowsWithoutAReadStamp() {
        val items = listOf(
            item("a", "2026-09-16T08:00:00+02:00"),
            item("b", "2026-09-16T08:01:00+02:00", readAt = "2026-09-16T08:05:00+02:00"),
            item("c", "2026-09-16T08:02:00+02:00"),
        )
        assertEquals(2, InboxGrouping.unreadCount(items))
        assertEquals(0, InboxGrouping.unreadCount(emptyList()))
    }

    @Test
    fun anEmptyInboxHasNoSections() {
        assertTrue(InboxGrouping.groupByDay(emptyList(), today, bratislava).isEmpty())
    }

    @Test
    fun onlyACalloutWithAQuestOpensOne() {
        assertEquals("q1", item("a", "2026-09-16T08:00:00Z", type = NotificationType.QUEST_CALLOUT, questId = "q1").opensQuestId)
        assertNull(item("b", "2026-09-16T08:00:00Z", type = NotificationType.QUEST_CALLOUT).opensQuestId)
        assertNull(item("c", "2026-09-16T08:00:00Z", type = NotificationType.GENERAL, questId = "q1").opensQuestId)
        assertNull(item("d", "2026-09-16T08:00:00Z", type = NotificationType.QUEST_CALLOUT, questId = " ").opensQuestId)
    }

    @Test
    fun anUnknownTypeIsKeptNotDropped() {
        assertEquals(NotificationType.UNKNOWN, NotificationType.fromWire("survey"))
        assertEquals(NotificationType.QUEST_CALLOUT, NotificationType.fromWire("quest_callout"))
        assertEquals(NotificationType.GENERAL, NotificationType.fromWire("general"))
        // The empty wire value of UNKNOWN itself must not match "general" by accident.
        assertEquals(NotificationType.UNKNOWN, NotificationType.fromWire(""))
    }
}
