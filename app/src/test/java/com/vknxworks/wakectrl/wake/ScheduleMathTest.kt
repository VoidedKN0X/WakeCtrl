package com.vknxworks.wakectrl.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ScheduleMathTest {
    private val brussels = ZoneId.of("Europe/Brussels")
    private val weekdays = (1..5).toSet()
    private val everyDay = (1..7).toSet()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = brussels): ZonedDateTime =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone)

    private fun w(start: String, end: String, days: Set<Int> = everyDay, id: Int = 1): ScheduleWindow {
        fun m(s: String) = s.substringBefore(':').toInt() * 60 + s.substringAfter(':').toInt()
        return ScheduleWindow(id, days, m(start), m(end))
    }

    // 2026-06-08 is a Monday, 2026-06-12 a Friday, 2026-06-13 a Saturday.

    @Test
    fun insideAndOutsideADayWindow() {
        val office = listOf(w("08:00", "17:30", weekdays))
        assertFalse(ScheduleMath.isInside(office, at(2026, 6, 8, 7, 59)))
        assertTrue(ScheduleMath.isInside(office, at(2026, 6, 8, 8, 0))) // start included
        assertTrue(ScheduleMath.isInside(office, at(2026, 6, 8, 12, 0)))
        assertTrue(ScheduleMath.isInside(office, at(2026, 6, 8, 17, 29)))
        assertFalse(ScheduleMath.isInside(office, at(2026, 6, 8, 17, 30))) // end excluded
        assertFalse(ScheduleMath.isInside(office, at(2026, 6, 13, 12, 0))) // Saturday
    }

    @Test
    fun nextChangeForADayWindow() {
        val office = listOf(w("08:00", "17:30", weekdays))
        assertEquals(at(2026, 6, 8, 8, 0), ScheduleMath.nextChange(office, at(2026, 6, 8, 6, 0)))
        assertEquals(at(2026, 6, 8, 17, 30), ScheduleMath.nextChange(office, at(2026, 6, 8, 8, 0)))
        assertEquals(at(2026, 6, 9, 8, 0), ScheduleMath.nextChange(office, at(2026, 6, 8, 17, 30)))
        // Friday evening: next is Monday morning.
        assertEquals(at(2026, 6, 15, 8, 0), ScheduleMath.nextChange(office, at(2026, 6, 12, 18, 0)))
    }

    @Test
    fun windowAcrossMidnightBelongsToItsStartDay() {
        // Friday nights only, 22:00 until 06:00 on Saturday.
        val night = listOf(w("22:00", "06:00", setOf(5)))
        assertTrue(night.first().crossesMidnight)
        assertFalse(ScheduleMath.isInside(night, at(2026, 6, 12, 21, 59)))
        assertTrue(ScheduleMath.isInside(night, at(2026, 6, 12, 22, 0)))
        assertTrue(ScheduleMath.isInside(night, at(2026, 6, 12, 23, 59)))
        assertTrue(ScheduleMath.isInside(night, at(2026, 6, 13, 0, 0)))
        assertTrue(ScheduleMath.isInside(night, at(2026, 6, 13, 5, 59)))
        assertFalse(ScheduleMath.isInside(night, at(2026, 6, 13, 6, 0)))
        // Thursday night isn't in it, nor is Saturday night.
        assertFalse(ScheduleMath.isInside(night, at(2026, 6, 12, 1, 0)))
        assertFalse(ScheduleMath.isInside(night, at(2026, 6, 13, 23, 0)))

        assertEquals(at(2026, 6, 13, 6, 0), ScheduleMath.nextChange(night, at(2026, 6, 12, 23, 0)))
        assertEquals(at(2026, 6, 13, 6, 0), ScheduleMath.nextChange(night, at(2026, 6, 13, 0, 30)))
        assertEquals(at(2026, 6, 19, 22, 0), ScheduleMath.nextChange(night, at(2026, 6, 13, 6, 0)))
    }

    @Test
    fun windowEndingAtMidnight() {
        val evening = listOf(w("18:00", "00:00"))
        assertTrue(ScheduleMath.isInside(evening, at(2026, 6, 8, 23, 59)))
        assertFalse(ScheduleMath.isInside(evening, at(2026, 6, 9, 0, 0)))
        assertEquals(at(2026, 6, 9, 0, 0), ScheduleMath.nextChange(evening, at(2026, 6, 8, 20, 0)))
    }

    @Test
    fun sameStartAndEndIsAWholeDay() {
        val day = listOf(w("09:00", "09:00", setOf(1)))
        assertTrue(ScheduleMath.isInside(day, at(2026, 6, 8, 9, 0)))
        assertTrue(ScheduleMath.isInside(day, at(2026, 6, 9, 8, 59)))
        assertFalse(ScheduleMath.isInside(day, at(2026, 6, 9, 9, 0)))
    }

    @Test
    fun backToBackAndOverlappingWindowsActAsOne() {
        val list = listOf(w("08:00", "12:00", id = 1), w("12:00", "17:00", id = 2), w("16:00", "18:00", id = 3))
        assertTrue(ScheduleMath.isInside(list, at(2026, 6, 8, 12, 0)))
        // No switch at 12:00 or 16:00/17:00: the next real change is the end at 18:00.
        assertEquals(at(2026, 6, 8, 18, 0), ScheduleMath.nextChange(list, at(2026, 6, 8, 9, 0)))
    }

    @Test
    fun alwaysOnAndEmptyHaveNoNextChange() {
        assertNull(ScheduleMath.nextChange(emptyList(), at(2026, 6, 8, 9, 0)))
        assertNull(ScheduleMath.nextChange(listOf(w("08:00", "17:00", emptySet())), at(2026, 6, 8, 9, 0)))
        val always = listOf(w("00:00", "00:00"))
        assertTrue(ScheduleMath.isInside(always, at(2026, 6, 8, 3, 0)))
        assertNull(ScheduleMath.nextChange(always, at(2026, 6, 8, 3, 0)))
    }

    @Test
    fun springForwardDay() {
        // 29 March 2026 (Sunday), Brussels: 02:00 CET jumps to 03:00 CEST.
        val office = listOf(w("08:00", "17:30"))
        val sat = at(2026, 3, 28, 20, 0)
        val start = ScheduleMath.nextChange(office, sat)!!
        assertEquals(LocalDateTime.of(2026, 3, 29, 8, 0), start.toLocalDateTime())
        assertEquals(ZoneOffset.ofHours(2), start.offset)
        // 23 hours of real time from Saturday 08:00 to Sunday 08:00... from 20:00 it's 11 real hours.
        assertEquals(Duration.ofHours(11), Duration.between(sat, start))

        // A window starting in the skipped hour starts when the clock jumps (03:30 summer time).
        val night = listOf(w("02:30", "05:00"))
        val s2 = ScheduleMath.nextChange(night, at(2026, 3, 29, 0, 0))!!
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), s2.toLocalDateTime())
        assertTrue(ScheduleMath.isInside(night, s2))
        assertEquals(LocalDateTime.of(2026, 3, 29, 5, 0), ScheduleMath.nextChange(night, s2)!!.toLocalDateTime())

        // A night window across the change lasts an hour less in real time.
        val overnight = listOf(w("22:00", "07:00", setOf(6)))
        val from = at(2026, 3, 28, 22, 0)
        assertTrue(ScheduleMath.isInside(overnight, from))
        val end = ScheduleMath.nextChange(overnight, from)!!
        assertEquals(LocalDateTime.of(2026, 3, 29, 7, 0), end.toLocalDateTime())
        assertEquals(Duration.ofHours(8), Duration.between(from, end))
    }

    @Test
    fun fallBackDay() {
        // 25 October 2026 (Sunday), Brussels: 03:00 CEST falls back to 02:00 CET.
        val overnight = listOf(w("22:00", "07:00", setOf(6)))
        val from = at(2026, 10, 24, 22, 0)
        val end = ScheduleMath.nextChange(overnight, from)!!
        assertEquals(LocalDateTime.of(2026, 10, 25, 7, 0), end.toLocalDateTime())
        assertEquals(ZoneOffset.ofHours(1), end.offset)
        assertEquals(Duration.ofHours(10), Duration.between(from, end))
        // Inside during both 02:30s.
        val first = LocalDateTime.of(2026, 10, 25, 2, 30).atZone(brussels).withEarlierOffsetAtOverlap()
        val second = first.withLaterOffsetAtOverlap()
        assertTrue(ScheduleMath.isInside(overnight, first))
        assertTrue(ScheduleMath.isInside(overnight, second))

        // A window ending in the repeated hour ends at its first 02:30.
        val early = listOf(w("01:00", "02:30", setOf(7)))
        val e = ScheduleMath.nextChange(early, at(2026, 10, 25, 1, 15))!!
        assertEquals(first.toInstant(), e.toInstant())
        assertFalse(ScheduleMath.isInside(early, second))
    }

    @Test
    fun timeZoneChangeUsesTheNewWallClock() {
        // The same instant is 09:00 in Brussels (inside) and 03:00 in New York (outside).
        val office = listOf(w("08:00", "17:30", weekdays))
        val instant = at(2026, 6, 8, 9, 0).toInstant()
        assertTrue(ScheduleMath.isInside(office, instant.atZone(brussels)))
        val ny = instant.atZone(ZoneId.of("America/New_York"))
        assertFalse(ScheduleMath.isInside(office, ny))
        assertEquals(LocalDateTime.of(2026, 6, 8, 8, 0), ScheduleMath.nextChange(office, ny)!!.toLocalDateTime())
    }

    @Test
    fun storageRoundTripAndBadInput() {
        val list = listOf(w("08:00", "17:30", weekdays, 1), w("22:00", "06:00", setOf(5), 2))
        assertEquals(list, ScheduleMath.decode(ScheduleMath.encode(list)))
        assertEquals(emptyList<ScheduleWindow>(), ScheduleMath.decode(null))
        assertEquals(emptyList<ScheduleWindow>(), ScheduleMath.decode("not json"))
        val cleaned = ScheduleMath.decode("""[{"id":1,"days":[0,1,9],"start":-5,"end":99999,"extra":true}]""").single()
        assertEquals(setOf(1), cleaned.days)
        assertEquals(0, cleaned.start)
        assertEquals(24 * 60 - 1, cleaned.end)
        val many = (1..50).map { w("08:00", "09:00", id = it) }
        assertEquals(ScheduleMath.MAX_WINDOWS, ScheduleMath.decode(ScheduleMath.encode(many)).size)
    }
}
