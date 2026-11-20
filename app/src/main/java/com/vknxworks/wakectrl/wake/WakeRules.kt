package com.vknxworks.wakectrl.wake

/** AppWake's per-app choice, worked out without Android so it can be unit-tested. */
object AppWakeRule {
    /**
     * Whether [pkg] on screen keeps the screen on: AppWake is on, the app is chosen, and, for apps set to
     * "Only while charging", the phone is charging.
     */
    fun keepsOn(pkg: String?, enabled: Boolean, packages: Set<String>, chargingOnly: Set<String>, charging: Boolean): Boolean =
        enabled && pkg != null && pkg in packages && (pkg !in chargingOnly || charging)
}

/** How long the service waits before it looks at its automations again, worked out without Android so it can be unit-tested. */
object PollRule {
    /** While AppWake or DevWake has to notice the app on screen changing. */
    const val QUICK_MS = 2_000L

    /** Otherwise: charging, Bluetooth and the schedule announce themselves, so this only catches the battery level and the long-session reminder. */
    const val SLOW_MS = 15_000L
    private const val MIN_MS = 250L

    /**
     * [watchingApps]: AppWake (with apps chosen) or DevWake is on. [manualUntil] and [scheduleUntil] are the moments
     * (epoch ms, 0 for none) the timer and the current schedule window end: the wait never runs past the nearest
     * one that is still ahead, so both end on time.
     */
    fun delayMs(watchingApps: Boolean, screenOn: Boolean, now: Long, manualUntil: Long, scheduleUntil: Long): Long {
        var wait = if (watchingApps && screenOn) QUICK_MS else SLOW_MS
        if (manualUntil > now) wait = minOf(wait, manualUntil - now)
        if (scheduleUntil > now) wait = minOf(wait, scheduleUntil - now)
        return wait.coerceAtLeast(MIN_MS)
    }
}
