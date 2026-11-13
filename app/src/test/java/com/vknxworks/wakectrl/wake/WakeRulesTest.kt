package com.vknxworks.wakectrl.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWakeRuleTest {
    private val maps = "org.maps"
    private val reader = "org.reader"
    private val chosen = setOf(maps, reader)
    private val chargingOnly = setOf(maps)

    @Test fun plainAppKeepsOnWithOrWithoutCharging() {
        assertTrue(AppWakeRule.keepsOn(reader, true, chosen, chargingOnly, charging = false))
        assertTrue(AppWakeRule.keepsOn(reader, true, chosen, chargingOnly, charging = true))
    }

    @Test fun chargingOnlyAppNeedsCharging() {
        assertFalse(AppWakeRule.keepsOn(maps, true, chosen, chargingOnly, charging = false))
        assertTrue(AppWakeRule.keepsOn(maps, true, chosen, chargingOnly, charging = true))
    }

    @Test fun appWakeOffOrAppNotChosen() {
        assertFalse(AppWakeRule.keepsOn(maps, false, chosen, chargingOnly, charging = true))
        assertFalse(AppWakeRule.keepsOn("org.other", true, chosen, chargingOnly, charging = true))
        assertFalse(AppWakeRule.keepsOn(null, true, chosen, chargingOnly, charging = true))
    }

    @Test fun leftoverChargingOnlyEntryForUnchosenAppDoesNothing() {
        assertFalse(AppWakeRule.keepsOn(maps, true, setOf(reader), chargingOnly, charging = true))
    }
}

class WidgetMathTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun offShowsNothing() {
        val i = WidgetMath.info(WakeStatus())
        assertFalse(i.active)
        assertNull(i.reason)
        assertEquals(0L, i.endsAt)
        assertEquals(0L, WidgetMath.nextTick(i.endsAt, t0))
    }

    @Test fun manualTimerIsShownWithItsEnd() {
        val i = WidgetMath.info(WakeStatus(running = true, reasons = setOf(Reason.MANUAL), manualUntil = t0 + 15 * min))
        assertEquals(Reason.MANUAL, i.reason)
        assertEquals(t0 + 15 * min, i.endsAt)
        assertEquals(0, i.more)
    }

    @Test fun manualWinsOverScheduleAndCountsTheOthers() {
        val s = WakeStatus(
            running = true, reasons = setOf(Reason.SCHEDULE, Reason.MANUAL, Reason.CHARGING),
            manualUntil = 0L, scheduleUntil = t0 + 90 * min,
        )
        val i = WidgetMath.info(s)
        assertEquals(Reason.MANUAL, i.reason)
        assertEquals(0L, i.endsAt) // manual without a timer: no countdown
        assertEquals(2, i.more)
    }

    @Test fun scheduleShowsItsWindowEnd() {
        val i = WidgetMath.info(WakeStatus(running = true, reasons = setOf(Reason.SCHEDULE, Reason.APP), scheduleUntil = t0 + 61 * min))
        assertEquals(Reason.SCHEDULE, i.reason)
        assertEquals(t0 + 61 * min, i.endsAt)
        assertEquals(1, i.more)
    }

    @Test fun automationWithoutEndHasNoCountdown() {
        val i = WidgetMath.info(WakeStatus(running = true, reasons = setOf(Reason.CHARGING, Reason.BLUETOOTH)))
        assertEquals(Reason.BLUETOOTH, i.reason)
        assertEquals(0L, i.endsAt)
        assertEquals(0L, WidgetMath.nextTick(i.endsAt, t0))
    }

    @Test fun minutesLeftRoundUp() {
        val end = t0 + 15 * min
        assertEquals(15L, WidgetMath.minutesLeft(end, t0))
        assertEquals(15L, WidgetMath.minutesLeft(end, t0 + 1))
        assertEquals(14L, WidgetMath.minutesLeft(end, t0 + min))
        assertEquals(1L, WidgetMath.minutesLeft(end, end - 1))
        assertEquals(0L, WidgetMath.minutesLeft(end, end))
        assertEquals(0L, WidgetMath.minutesLeft(end, end + 5 * min))
        assertEquals(0L, WidgetMath.minutesLeft(0L, t0))
    }

    @Test fun nextTickIsWhenTheShownMinuteChanges() {
        // A timer started at an odd second: ticks are counted back from its end, not from the clock.
        val end = t0 + 15 * min + 23_456
        val now = t0
        val tick = WidgetMath.nextTick(end, now)
        assertEquals(t0 + 23_456, tick)
        assertTrue(tick > now)
        assertEquals(WidgetMath.minutesLeft(end, now) - 1, WidgetMath.minutesLeft(end, tick))
        // Exactly on a minute boundary: the next one is a full minute away.
        assertEquals(now + min, WidgetMath.nextTick(now + 10 * min, now))
        // The last minute: the next tick is the end itself, after which nothing is scheduled.
        assertEquals(end, WidgetMath.nextTick(end, end - 30_000))
        assertEquals(0L, WidgetMath.nextTick(end, end))
    }

    @Test fun scheduleTicksFallOnClockMinutes() {
        // A window ending at a whole clock minute: every tick is a whole clock minute too.
        val end = (t0 / min + 45) * min
        var now = t0 + 12_345
        repeat(45) {
            val tick = WidgetMath.nextTick(end, now)
            assertEquals(0L, tick % min)
            assertTrue(tick > now)
            now = tick
        }
        assertEquals(end, now)
        assertEquals(0L, WidgetMath.nextTick(end, now))
    }
}
