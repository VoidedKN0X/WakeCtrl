package com.vknxworks.wakectrl.widget

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import android.widget.Toast
import com.vknxworks.wakectrl.R
import com.vknxworks.wakectrl.container
import com.vknxworks.wakectrl.wake.ActivityLog
import com.vknxworks.wakectrl.wake.Reason
import com.vknxworks.wakectrl.wake.ScheduleAlarm
import com.vknxworks.wakectrl.wake.WakeService
import com.vknxworks.wakectrl.wake.WakeState
import com.vknxworks.wakectrl.wake.WidgetMath
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: tap to keep the screen on, tap again to stop. */
class WakeTileService : TileService() {
    // While the Quick Settings panel is open the tile follows WakeState live. (requestListeningState does
    // nothing while the tile is already listening, so a tap used to show its result only on the next open.)
    private var scope: kotlinx.coroutines.CoroutineScope? = null

    override fun onStartListening() {
        update(WakeState.status.value.active)
        scope?.cancel()
        scope = kotlinx.coroutines.MainScope().also { s ->
            s.launch { WakeState.status.collect { update(it.active) } }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        val on = WakeState.status.value.manual
        val ok = WakeService.send(this, if (on) WakeService.ACTION_OFF else WakeService.ACTION_ON, getString(R.string.log_source_tile))
        if (!ok) {
            Toast.makeText(this, getString(R.string.start_blocked), Toast.LENGTH_LONG).show()
            return
        }
        // Show the result of the tap straight away; the live state confirms (or corrects) it a moment later.
        // Turning manual off can still leave the screen held by an automation, so only an "on" tap is shown early.
        if (!on) update(true)
    }

    private fun update(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = getString(if (active) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, WakeTileService::class.java)) }
        }
    }
}

/**
 * Home-screen widget: one tap toggles keeping the screen on. While on, it shows why (the main reason) and, for the
 * manual timer or a ScheduleWake window, the minutes left. It redraws only when what it shows changes; while a
 * countdown runs, one non-waking alarm at the next minute keeps it current. Nothing runs while it's off.
 */
class WakeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids, force = true)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle?) {
        render(context, manager, intArrayOf(id), force = true)
    }

    override fun onDisabled(context: Context) {
        cancelTick(context)
        lastShown = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_TOGGLE -> WakeService.send(context, WakeService.ACTION_TOGGLE, context.getString(R.string.log_source_widget))
            ACTION_WIDGET_TICK -> {
                tickAt = 0L
                refresh(context)
            }
        }
    }

    /** What the widget shows; it's redrawn only when this changes. */
    private data class Shown(val on: Boolean, val main: String, val reason: String?)

    companion object {
        private const val ACTION_WIDGET_TOGGLE = "com.vknxworks.wakectrl.action.WIDGET_TOGGLE"
        private const val ACTION_WIDGET_TICK = "com.vknxworks.wakectrl.action.WIDGET_TICK"

        @Volatile private var lastShown: Shown? = null
        /** The minute alarm that is set (epoch ms), 0 when none. */
        @Volatile private var tickAt = 0L

        /** Called whenever WakeState changes (the service checks often): cheap when nothing visible changed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, WakeWidget::class.java))
            if (ids.isEmpty()) {
                if (tickAt != 0L) cancelTick(context)
                return
            }
            render(context, manager, ids, force = false)
        }

        @Synchronized
        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, force: Boolean) {
            val now = System.currentTimeMillis()
            val info = WidgetMath.info(WakeState.status.value)
            val minutes = WidgetMath.minutesLeft(info.endsAt, now)
            val shown = Shown(
                on = info.active,
                main = when {
                    !info.active -> context.getString(R.string.tile_off)
                    minutes > 0 -> leftText(context, minutes)
                    else -> context.getString(R.string.tile_on)
                },
                reason = when {
                    info.active -> info.reason?.let { r ->
                        val label = context.getString(widgetReason(r))
                        if (info.more > 0) context.getString(R.string.widget_reason_more, label, info.more) else label
                    }
                    info.stoodDown -> context.getString(R.string.widget_low_battery)
                    else -> null
                },
            )
            // Only while a countdown is shown: one alarm at the next minute. Off (or no end): no alarm at all.
            scheduleTick(context, if (info.active) WidgetMath.nextTick(info.endsAt, now) else 0L)
            if (!force && shown == lastShown) return
            lastShown = shown

            val tap = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, WakeWidget::class.java).setAction(ACTION_WIDGET_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE,
            )
            fun views(layout: Int) = RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.widget_state, shown.main)
                setImageViewResource(R.id.widget_icon, if (shown.on) R.drawable.ic_widget_on else R.drawable.ic_widget_off)
                setTextViewText(R.id.widget_reason, shown.reason.orEmpty())
                setViewVisibility(R.id.widget_reason, if (shown.reason != null) android.view.View.VISIBLE else android.view.View.GONE)
                setContentDescription(
                    R.id.widget_root,
                    listOfNotNull(context.getString(R.string.app_name), shown.main, shown.reason).joinToString(", "),
                )
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
            // A compact layout for the usual one-cell widget, a roomier one when it's made taller.
            val v = RemoteViews(
                mapOf(
                    android.util.SizeF(40f, 40f) to views(R.layout.widget_wake_small),
                    android.util.SizeF(40f, 100f) to views(R.layout.widget_wake),
                ),
            )
            for (id in ids) manager.updateAppWidget(id, v)
        }

        /** "12 min left", "1 h 05 left". */
        private fun leftText(context: Context, minutes: Long): String =
            if (minutes < 60) context.getString(R.string.widget_left_min, minutes.toInt())
            else context.getString(R.string.widget_left_hours, (minutes / 60).toInt(), (minutes % 60).toInt())

        private fun widgetReason(r: Reason) = when (r) {
            Reason.MANUAL -> R.string.widget_reason_manual
            Reason.APP -> R.string.widget_reason_app
            Reason.CHARGING -> R.string.widget_reason_charging
            Reason.BLUETOOTH -> R.string.widget_reason_bluetooth
            Reason.DEBUG_APP -> R.string.widget_reason_debug
            Reason.SCHEDULE -> R.string.widget_reason_schedule
        }

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, WakeWidget::class.java).setAction(ACTION_WIDGET_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Sets the minute alarm for [at] (or cancels it for 0). It doesn't wake the phone (RTC, not RTC_WAKEUP): with
         * the screen off nobody sees the widget, and Android delivers it when the phone wakes. On the minute when
         * "Alarms and reminders" is allowed (ScheduleWake asks for it), otherwise as close as Android allows.
         */
        private fun scheduleTick(context: Context, at: Long) {
            if (at == tickAt) return
            if (at == 0L) {
                cancelTick(context)
                return
            }
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val ok = runCatching {
                if (ScheduleAlarm.canExact(context)) am.setExact(AlarmManager.RTC, at, tickIntent(context))
                else am.set(AlarmManager.RTC, at, tickIntent(context))
            }.isSuccess
            tickAt = if (ok) at else 0L
        }

        private fun cancelTick(context: Context) {
            runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(context)) }
            tickAt = 0L
        }
    }
}

/** Launcher shortcut ("Toggle WakeCtrl"): toggles and closes without showing anything. */
class ToggleShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WakeService.send(this, WakeService.ACTION_TOGGLE, getString(R.string.log_source_shortcut))
        finish()
    }
}

/**
 * Lets automation apps (Tasker, Home Assistant, MacroDroid…) turn WakeCtrl on and off with an explicit
 * broadcast: com.vknxworks.wakectrl.action.ON / OFF / TOGGLE. The component is disabled (unreachable) while
 * "Allow other apps" is off, and the setting is checked again for every broadcast. Extras are never read.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(WakeService.ACTION_ON, WakeService.ACTION_OFF, WakeService.ACTION_TOGGLE)) return
        ActivityLog.init(context)
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                // Read from storage: right after a cold start the in-memory settings may not be loaded yet.
                if (!c.settings.settingsLoaded().allowAutomation) {
                    ActivityLog.add(context.getString(R.string.log_automation_blocked))
                } else {
                    WakeService.send(context, action, context.getString(R.string.log_source_automation))
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Turns the receiver on or off to match "Allow other apps" (a no-op when it already matches). */
        fun setEnabled(context: Context, on: Boolean) {
            val pm = context.packageManager
            val component = ComponentName(context, AutomationReceiver::class.java)
            val want = if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching {
                if (pm.getComponentEnabledSetting(component) != want) {
                    pm.setComponentEnabledSetting(component, want, android.content.pm.PackageManager.DONT_KILL_APP)
                }
            }
        }
    }
}

/**
 * After a restart: resume a manual keep-awake (if set) and start watching for automations. After a change of time
 * or time zone, or when exact alarms are allowed again: work the Schedule out again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        val clock = intent.action in setOf(
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        if (!boot && !clock) return
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                val s = c.settings.settingsLoaded()
                com.vknxworks.wakectrl.wake.ScheduleAlarm.forget()
                val resume = boot && s.resumeAfterRestart && c.settings.wasManualOnNow()
                when {
                    resume -> WakeService.send(context, WakeService.ACTION_RESUME)
                    s.anyAutomation -> WakeService.send(context, WakeService.ACTION_REFRESH)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
