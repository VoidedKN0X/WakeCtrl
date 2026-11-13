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

/** What the home-screen widget shows: the main reason and, when it has an end, the time left. */
data class WidgetInfo(
    val active: Boolean,
    /** The reason shown; null when off. */
    val reason: Reason?,
    /** How many other reasons also keep the screen on. */
    val more: Int,
    /** When the shown reason ends (epoch ms): the manual timer or the Schedule window's end; 0 for no end. */
    val endsAt: Long,
    val stoodDown: Boolean,
)

/** The widget's maths, kept free of Android so it can be unit-tested. */
object WidgetMath {
    private const val MINUTE = 60_000L

    /** The reason the widget names when several apply: what you did yourself first, then the timed ones. */
    val PRIORITY = listOf(Reason.MANUAL, Reason.SCHEDULE, Reason.APP, Reason.DEBUG_APP, Reason.BLUETOOTH, Reason.CHARGING)

    fun info(s: WakeStatus): WidgetInfo {
        val reason = PRIORITY.firstOrNull { it in s.reasons }
        val endsAt = when (reason) {
            Reason.MANUAL -> s.manualUntil
            Reason.SCHEDULE -> s.scheduleUntil
            else -> 0L
        }
        return WidgetInfo(
            active = s.active,
            reason = reason,
            more = (s.reasons.size - 1).coerceAtLeast(0),
            endsAt = endsAt.coerceAtLeast(0L),
            stoodDown = s.stoodDown,
        )
    }

    /** Whole minutes left, rounded up (so "1 min" shows until the very end); 0 with no end or once it has passed. */
    fun minutesLeft(endsAt: Long, now: Long): Long {
        if (endsAt <= 0L || endsAt <= now) return 0L
        return (endsAt - now + MINUTE - 1) / MINUTE
    }

    /**
     * When the minutes shown next change (epoch ms): the next whole minute counted back from [endsAt]. For a
     * Schedule window that is the next minute on the clock. 0 when nothing counts down, so no alarm is needed.
     */
    fun nextTick(endsAt: Long, now: Long): Long {
        val m = minutesLeft(endsAt, now)
        if (m == 0L) return 0L
        return endsAt - (m - 1) * MINUTE
    }
}
