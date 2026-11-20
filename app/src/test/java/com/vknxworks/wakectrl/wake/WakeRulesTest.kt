package com.vknxworks.wakectrl.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

class PollRuleTest {
    private val now = 1_000_000L

    @Test fun quickOnlyWhileWatchingAppsWithTheScreenOn() {
        assertEquals(2_000L, PollRule.delayMs(watchingApps = true, screenOn = true, now = now, manualUntil = 0, scheduleUntil = 0))
        assertEquals(15_000L, PollRule.delayMs(watchingApps = true, screenOn = false, now = now, manualUntil = 0, scheduleUntil = 0))
        assertEquals(15_000L, PollRule.delayMs(watchingApps = false, screenOn = true, now = now, manualUntil = 0, scheduleUntil = 0))
    }

    @Test fun neverWaitsPastTheTimerOrTheScheduleEnd() {
        assertEquals(4_000L, PollRule.delayMs(false, true, now, manualUntil = now + 4_000, scheduleUntil = 0))
        assertEquals(3_000L, PollRule.delayMs(false, true, now, manualUntil = now + 4_000, scheduleUntil = now + 3_000))
        assertEquals(1_500L, PollRule.delayMs(true, true, now, manualUntil = 0, scheduleUntil = now + 1_500))
        // Far ahead: the ordinary wait.
        assertEquals(15_000L, PollRule.delayMs(false, true, now, manualUntil = now + 3_600_000, scheduleUntil = now + 60_000))
        assertEquals(2_000L, PollRule.delayMs(true, true, now, manualUntil = now + 3_600_000, scheduleUntil = 0))
    }

    @Test fun momentsThatPassedAreIgnoredAndTheWaitIsNeverTiny() {
        assertEquals(15_000L, PollRule.delayMs(false, false, now, manualUntil = now - 1, scheduleUntil = now))
        assertEquals(250L, PollRule.delayMs(false, true, now, manualUntil = now + 10, scheduleUntil = 0))
    }
}
