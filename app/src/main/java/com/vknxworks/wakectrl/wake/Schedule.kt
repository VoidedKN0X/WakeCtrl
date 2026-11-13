package com.vknxworks.wakectrl.wake

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * One time window of the Schedule automation, for example weekdays 08:00–17:30.
 *
 * [days] are ISO days (1 = Monday … 7 = Sunday) on which the window *starts*. When [end] is at or before [start]
 * the window runs past midnight and ends the next day (22:00–06:00 on Friday ends on Saturday morning); start and
 * end the same is a whole day. Times are minutes after midnight on the wall clock, so 08:00 stays 08:00 after a
 * daylight-saving change.
 */
@Serializable
data class ScheduleWindow(
    val id: Int,
    val days: Set<Int> = (1..5).toSet(),
    val start: Int = 8 * 60,
    val end: Int = 17 * 60 + 30,
) {
    val crossesMidnight: Boolean get() = end <= start

    /** Cleans a window read from storage: times within the day, days 1..7. */
    fun sanitized(): ScheduleWindow = copy(
        days = days.filter { it in 1..7 }.toSet(),
        start = start.coerceIn(0, MINUTES_PER_DAY - 1),
        end = end.coerceIn(0, MINUTES_PER_DAY - 1),
    )

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/** The window maths, free of Android so it can be unit-tested. */
object ScheduleMath {
    const val MAX_WINDOWS = 20

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = kotlinx.serialization.builtins.ListSerializer(ScheduleWindow.serializer())

    fun encode(windows: List<ScheduleWindow>): String = json.encodeToString(listSerializer, windows.take(MAX_WINDOWS))

    /** Windows from storage; anything unreadable is dropped rather than closing the app. */
    fun decode(text: String?): List<ScheduleWindow> {
        if (text.isNullOrBlank() || text.length > 20_000) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, text) }.getOrDefault(emptyList())
            .take(MAX_WINDOWS).map { it.sanitized() }
    }

    /**
     * The time the window that starts on [date] is open, or null when it doesn't start that day. Wall-clock times
     * that don't exist (the hour skipped when clocks go forward) move forward by the gap; times that happen twice
     * (the hour repeated when clocks go back) use the first one.
     */
    fun occurrence(w: ScheduleWindow, date: LocalDate, zone: java.time.ZoneId): Pair<ZonedDateTime, ZonedDateTime>? {
        if (date.dayOfWeek.value !in w.days) return null
        val start = LocalDateTime.of(date, time(w.start)).atZone(zone)
        val endDate = if (w.crossesMidnight) date.plusDays(1) else date
        val end = LocalDateTime.of(endDate, time(w.end)).atZone(zone)
        // Only possible around a clock change: a window that would end before it starts doesn't open.
        if (!end.isAfter(start)) return null
        return start to end
    }

    private fun time(minutes: Int): LocalTime = LocalTime.of(minutes / 60, minutes % 60)

    /** The window [now] falls in (start included, end not), or null. */
    fun activeWindow(windows: List<ScheduleWindow>, now: ZonedDateTime): ScheduleWindow? {
        val today = now.toLocalDate()
        for (w in windows) {
            for (d in listOf(today.minusDays(1), today)) {
                val (s, e) = occurrence(w, d, now.zone) ?: continue
                if (!now.isBefore(s) && now.isBefore(e)) return w
            }
        }
        return null
    }

    fun isInside(windows: List<ScheduleWindow>, now: ZonedDateTime): Boolean = activeWindow(windows, now) != null

    /**
     * The next moment the schedule switches (from outside to inside or back), or null when it never does (no
     * windows, or windows that cover every minute of the week). Back-to-back or overlapping windows count as one.
     */
    fun nextChange(windows: List<ScheduleWindow>, now: ZonedDateTime): ZonedDateTime? {
        if (windows.none { it.days.isNotEmpty() }) return null
        val inside = isInside(windows, now)
        val today = now.toLocalDate()
        val boundaries = buildList {
            for (offset in -1L..8L) {
                val d = today.plusDays(offset)
                for (w in windows) {
                    val (s, e) = occurrence(w, d, now.zone) ?: continue
                    add(s)
                    add(e)
                }
            }
        }.filter { it.isAfter(now) }.distinctBy { it.toInstant() }.sortedBy { it.toInstant() }
        return boundaries.firstOrNull { isInside(windows, it) != inside }
    }
}
