package com.vknxworks.wakectrl.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carrying on a manual keep-awake after Android restarted the service. */
class ManualResumeRuleTest {
    private val now = 1_700_000_000_000L

    @Test
    fun offIsNeverResumed() {
        assertFalse(ManualResumeRule.stillRuns(false, 0L, now))
        assertFalse(ManualResumeRule.stillRuns(false, now + 60_000L, now))
    }

    @Test
    fun noLimitAlwaysResumes() {
        assertTrue(ManualResumeRule.stillRuns(true, 0L, now))
        assertEquals(0, ManualResumeRule.minutesLeft(0L, now))
    }

    @Test
    fun aTimerResumesOnlyUntilItEnds() {
        assertTrue(ManualResumeRule.stillRuns(true, now + 1L, now))
        assertFalse(ManualResumeRule.stillRuns(true, now, now))
        assertFalse(ManualResumeRule.stillRuns(true, now - 5 * 60_000L, now))
    }

    @Test
    fun minutesLeftRoundsUp() {
        assertEquals(1, ManualResumeRule.minutesLeft(now + 1L, now))
        assertEquals(1, ManualResumeRule.minutesLeft(now + 60_000L, now))
        assertEquals(2, ManualResumeRule.minutesLeft(now + 60_001L, now))
        assertEquals(30, ManualResumeRule.minutesLeft(now + 30 * 60_000L, now))
        assertEquals(0, ManualResumeRule.minutesLeft(now - 1L, now))
    }
}
