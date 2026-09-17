package sk.martinvanco.monad.notifications.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** One day's worth of inbox rows under one header. */
data class InboxSection(
    val label: String,
    val day: LocalDate,
    val items: List<InboxItem>,
)

/**
 * The inbox's shape on screen: newest first, cut into days, with the two days a person names by
 * word rather than by date.
 *
 * `today` and the zone are parameters rather than read from a clock, so the boundary cases —
 * a message sent at 23:59 read at 00:01, a phone whose zone differs from the server's — are
 * checked in a test instead of noticed by a student.
 */
object InboxGrouping {

    fun groupByDay(items: List<InboxItem>, today: LocalDate, zone: TimeZone): List<InboxSection> =
        items
            .sortedByDescending { it.sentAt }
            .groupBy { it.sentAt.toLocalDateTime(zone).date }
            .entries
            .sortedByDescending { it.key }
            .map { (day, rows) -> InboxSection(label = label(day, today), day = day, items = rows) }

    fun unreadCount(items: List<InboxItem>): Int = items.count { it.isUnread }

    /** `Today`, `Yesterday`, then `16 Sep 2026`. The year is always written: a beta runs across one. */
    fun label(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        today.minusOneDay() -> "Yesterday"
        // `dayOfMonth` / `monthNumber` rather than the 0.7 names: the iOS target resolves the
        // pinned kotlinx-datetime 0.6.1 while Android pulls a 0.7 line transitively, and these two
        // are the spellings both accept.
        else -> "${day.dayOfMonth} ${MONTHS[day.monthNumber - 1]} ${day.year}"
    }

    private fun LocalDate.minusOneDay(): LocalDate = LocalDate.fromEpochDays(toEpochDays() - 1)

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}
