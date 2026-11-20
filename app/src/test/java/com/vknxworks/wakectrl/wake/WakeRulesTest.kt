package com.vknxworks.wakectrl.wake

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
