package net.morsecode.media

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Section E.3 — day-level grouping with local-timezone-correct headers.
 *
 * Used by PhotosTab, VideosTab and HistoryTab. Buckets are computed in Kotlin
 * (never in SQL) so the day boundary honours the device's timezone — the reason
 * a photo taken at 23:50 and one at 00:10 must land in different sections even
 * though they are 20 minutes apart.
 *
 * Groups are returned newest-day-first, matching the reference screenshots;
 * within a day, input order is preserved.
 */
object DateGrouping {

    data class DayGroup<T>(
        val day: LocalDate,
        /** Formatted like "August 26, 2026". */
        val header: String,
        val items: List<T>,
    )

    fun <T> groupByDay(
        items: List<T>,
        epochMs: (T) -> Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<DayGroup<T>> {
        val buckets = LinkedHashMap<LocalDate, MutableList<T>>()
        for (item in items) {
            val date = Instant.fromEpochMilliseconds(epochMs(item)).toLocalDateTime(timeZone).date
            buckets.getOrPut(date) { mutableListOf() }.add(item)
        }
        return buckets.entries
            .sortedByDescending { it.key }
            .map { entry -> DayGroup(entry.key, headerFor(entry.key), entry.value) }
    }

    fun headerFor(date: LocalDate): String = "${MONTH_NAMES[date.monthNumber - 1]} ${date.day}, ${date.year}"

    private val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
}
