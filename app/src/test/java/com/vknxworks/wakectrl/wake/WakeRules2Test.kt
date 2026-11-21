package com.vknxworks.wakectrl.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerRuleTest {
    @Test fun extraAcceptsWholeMinutesInRange() {
        assertEquals(30, TimerRule.parseExtra(30))
        assertEquals(30, TimerRule.parseExtra(30L))
        assertEquals(45, TimerRule.parseExtra(" 45 "))
        assertEquals(0, TimerRule.parseExtra("0"))
        assertEquals(1440, TimerRule.parseExtra(1440))
    }

    @Test fun extraRejectsEverythingElse() {
        assertNull(TimerRule.parseExtra(-1))
        assertNull(TimerRule.parseExtra(1441))
        assertNull(TimerRule.parseExtra(Long.MAX_VALUE))
        assertNull(TimerRule.parseExtra("1.5"))
        assertNull(TimerRule.parseExtra("ten"))
        assertNull(TimerRule.parseExtra(""))
        assertNull(TimerRule.parseExtra(2.0))
        assertNull(TimerRule.parseExtra(null))
    }

    @Test fun customTimer() {
        assertEquals(45, TimerRule.parseCustom("45"))
        assertNull(TimerRule.parseCustom("0"))
        assertNull(TimerRule.parseCustom("1441"))
        assertNull(TimerRule.parseCustom("-5"))
        assertNull(TimerRule.parseCustom(""))
    }

    @Test fun stepsAreSortedWithNoLimitLast() {
        assertEquals(listOf(15, 30, 0), TimerRule.sortSteps(listOf(0, 30, 15, 30)))
        assertEquals("5,60,0", TimerRule.encodeSteps(listOf(60, 0, 5)))
        assertEquals(listOf(5, 60, 0), TimerRule.decodeSteps("5,60,0"))
        assertEquals(listOf(5), TimerRule.decodeSteps("5,x,-3,99999"))
        assertNull(TimerRule.decodeSteps(null))
    }

    @Test fun cycleGoesThroughTheStepsThenOff() {
        val steps = listOf(15, 30, 0)
        assertEquals(15, TimerRule.nextStep(null, steps))
        assertEquals(30, TimerRule.nextStep(15, steps))
        assertEquals(0, TimerRule.nextStep(30, steps))
        assertNull(TimerRule.nextStep(0, steps))
        assertNull(TimerRule.nextStep(45, steps))
    }

    @Test fun cycleWithoutStepsIsAPlainToggle() {
        assertEquals(0, TimerRule.nextStep(null, emptyList()))
        assertNull(TimerRule.nextStep(15, emptyList()))
    }
}

class HeatRuleTest {
    @Test fun offOrUnknownIsNeverHot() {
        assertFalse(HeatRule.isHot(false, 500, 0))
        assertFalse(HeatRule.isHot(true, null, 42))
    }

    @Test fun getsHotAtTheLimitAndCoolsThreeDegreesUnder() {
        assertFalse(HeatRule.isHot(false, 419, 42))
        assertTrue(HeatRule.isHot(false, 420, 42))
        assertTrue(HeatRule.isHot(true, 400, 42))
        assertTrue(HeatRule.isHot(true, 391, 42))
        assertFalse(HeatRule.isHot(true, 390, 42))
    }
}

class AppTimerRuleTest {
    @Test fun noLimitNeverExpires() {
        assertFalse(AppTimerRule.expired(0, 0L, Long.MAX_VALUE / 2))
    }

    @Test fun expiresAfterTheLimit() {
        assertFalse(AppTimerRule.expired(15, 1_000L, 1_000L + 15 * 60_000L - 1))
        assertTrue(AppTimerRule.expired(15, 1_000L, 1_000L + 15 * 60_000L))
    }

    @Test fun storageRoundTripDropsBadEntries() {
        val stored = AppTimerRule.encode(mapOf("org.reader" to 30, "org.maps" to 0, "bad=pkg" to 5))
        assertEquals(setOf("org.reader=30"), stored)
        assertEquals(mapOf("org.reader" to 30), AppTimerRule.decode(stored + setOf("x", "org.y=abc", "=5", "org.z=99999")))
    }
}
