package com.vknxworks.wakectrl.wake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vknxworks.wakectrl.data.settings.AppSettings
import java.time.ZonedDateTime

/**
 * Wakes WakeCtrl at the next start or end of a Schedule window, so it switches on time even when the phone has been
 * idle. Exact when Android allows it ("Alarms and reminders"), otherwise as close as Android allows. The service also
 * re-checks the schedule every time it looks at the other automations.
 */
object ScheduleAlarm {
    /** The change the alarm is set for (epoch ms), 0 when none. */
    @Volatile private var setFor = 0L
    @Volatile private var cleared = false

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canExact(context: Context): Boolean =
        runCatching { context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true }.getOrDefault(false)

    /** Sets (or cancels) the alarm for the next change of [s]'s schedule. Cheap when nothing changed. */
    fun update(context: Context, s: AppSettings, now: ZonedDateTime = ZonedDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = if (s.scheduleWake) ScheduleMath.nextChange(s.scheduleWindows, now) else null
        if (next == null) {
            // Cancel once per process too, in case an alarm from before a restart is still set.
            if (setFor != 0L || !cleared) {
                am.cancel(intent(context))
                setFor = 0L
                cleared = true
            }
            return
        }
        val at = next.toInstant().toEpochMilli()
        if (at == setFor) return
        val pi = intent(context)
        val exact = canExact(context)
        val ok = runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.isSuccess
        setFor = if (ok) at else 0L
        cleared = false
    }

    /** After a clock, time zone or permission change: work the next change out again. */
    fun forget() {
        setFor = 0L
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent(context))
        setFor = 0L
    }
}

/** A Schedule window starts or ends: the service looks again. Not exported; only WakeCtrl's own alarm reaches it. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        ScheduleAlarm.forget()
        WakeService.send(context, WakeService.ACTION_REFRESH)
    }

    companion object {
        const val ACTION = "com.vknxworks.wakectrl.action.SCHEDULE"
    }
}
