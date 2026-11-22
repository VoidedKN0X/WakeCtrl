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
    /** While AppWake has to notice the app on screen changing. */
    const val QUICK_MS = 2_000L

    /** Otherwise: charging, Bluetooth and the schedule announce themselves, so this only catches the battery level and the long-session reminder. */
    const val SLOW_MS = 15_000L
    private const val MIN_MS = 250L

    /**
     * [watchingApps]: AppWake is on with apps chosen. [manualUntil] and [scheduleUntil] are the moments
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

/** Timer lengths, worked out without Android so they can be unit-tested. */
object TimerRule {
    /** The longest timer: a day. */
    const val MAX_MINUTES = 24 * 60

    /**
     * The `minutes` extra on an automation broadcast: a whole number from 0 (no limit) to [MAX_MINUTES], sent as a
     * number or as text. Anything else (negative, too long, a fraction, not a number) gives null.
     */
    fun parseExtra(raw: Any?): Int? {
        val n: Long = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is Short -> raw.toLong()
            is Byte -> raw.toLong()
            is String -> raw.trim().takeIf { it.length in 1..6 }?.toLongOrNull() ?: return null
            else -> return null
        }
        return if (n in 0..MAX_MINUTES.toLong()) n.toInt() else null
    }

    /** A custom timer typed in by hand: 1 to [MAX_MINUTES] minutes, or null. */
    fun parseCustom(text: String): Int? =
        text.trim().takeIf { it.length in 1..4 && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it in 1..MAX_MINUTES }

    /** Stored tile steps ("15,30,60,0"): valid lengths only, no repeats, at most 8; 0 means no limit. */
    fun decodeSteps(text: String?): List<Int>? {
        if (text == null) return null
        return text.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..MAX_MINUTES }.distinct().take(8)
    }

    fun encodeSteps(steps: List<Int>): String = sortSteps(steps).joinToString(",")

    /** Shortest first, "no limit" (0) last. */
    fun sortSteps(steps: List<Int>): List<Int> =
        steps.filter { it in 0..MAX_MINUTES }.distinct().sortedBy { if (it == 0) Int.MAX_VALUE else it }

    /**
     * Tap-to-cycle on the Quick Settings tile. [current] is the length the manual keep-awake is running with
     * (0 for no limit), or null when it's off. Gives the next length, or null to turn it off: off → the first
     * step → the next … → after the last step, off. A length that isn't one of the steps (turned on elsewhere) is
     * followed by off, like a plain tap.
     */
    fun nextStep(current: Int?, steps: List<Int>): Int? {
        if (steps.isEmpty()) return if (current == null) 0 else null
        if (current == null) return steps.first()
        val i = steps.indexOf(current)
        return if (i >= 0 && i + 1 < steps.size) steps[i + 1] else null
    }
}

/** "Stop when the battery is hot", worked out without Android so it can be unit-tested. */
object HeatRule {
    /** It counts as cooled down again 3 °C under the limit, so it doesn't flip on and off around it. */
    const val COOL_MARGIN_TENTHS = 30

    /**
     * Whether the battery counts as hot. [tenths] is the battery temperature in tenths of a degree Celsius (as
     * Android reports it), or null when unknown; [limitC] the chosen limit in °C, 0 for off; [wasHot] the last answer.
     */
    fun isHot(wasHot: Boolean, tenths: Int?, limitC: Int): Boolean {
        if (limitC <= 0 || tenths == null) return false
        val limit = limitC * 10
        return if (wasHot) tenths > limit - COOL_MARGIN_TENTHS else tenths >= limit
    }
}

/** AppWake's per-app time limit, worked out without Android so it can be unit-tested. */
object AppTimerRule {
    /**
     * Whether an app with a time limit of [limitMinutes] (0: none), on screen since [since] (epoch ms), has used up
     * its time at [now].
     */
    fun expired(limitMinutes: Int, since: Long, now: Long): Boolean = limitMinutes > 0 && now - since >= limitMinutes * 60_000L

    /** Stored as "package=minutes" entries; anything malformed or out of range is dropped. */
    fun decode(entries: Set<String>?): Map<String, Int> = entries.orEmpty().mapNotNull { e ->
        val i = e.lastIndexOf('=')
        if (i <= 0) return@mapNotNull null
        val m = e.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
        if (m !in 1..TimerRule.MAX_MINUTES) null else e.substring(0, i) to m
    }.toMap()

    fun encode(timers: Map<String, Int>): Set<String> =
        timers.filter { (pkg, m) -> pkg.isNotBlank() && '=' !in pkg && m in 1..TimerRule.MAX_MINUTES }.map { (pkg, m) -> "$pkg=$m" }.toSet()
}

/**
 * A manual keep-awake that was running when Android restarted the service, worked out without Android so it can be
 * unit-tested. [until] is when it ends (epoch ms, 0: no limit); [minutes] the length it was started with.
 */
object ManualResumeRule {
    /** Whether a keep-awake saved as ([on], [until]) still runs at [now]. */
    fun stillRuns(on: Boolean, until: Long, now: Long): Boolean = on && (until <= 0L || until > now)

    /** Whole minutes left (rounded up, for the activity log); 0 for no limit. */
    fun minutesLeft(until: Long, now: Long): Int =
        if (until <= 0L) 0 else (((until - now).coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()
}
