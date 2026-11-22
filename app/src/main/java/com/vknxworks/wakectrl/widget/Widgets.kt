package com.vknxworks.wakectrl.widget

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.vknxworks.wakectrl.R
import com.vknxworks.wakectrl.container
import com.vknxworks.wakectrl.wake.ActivityLog
import com.vknxworks.wakectrl.wake.TimerRule
import com.vknxworks.wakectrl.wake.minutesText
import com.vknxworks.wakectrl.wake.WakeService
import com.vknxworks.wakectrl.wake.WakeState
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
        // The service decides what a tap does: a plain on/off, or the next timer length with "Tap to cycle".
        val ok = WakeService.send(this, WakeService.ACTION_TILE, getString(R.string.log_source_tile))
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
        val status = WakeState.status.value
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        // While your own keep-awake runs with a timer, the tile shows its length (handy with "Tap to cycle").
        tile.subtitle = when {
            active && status.manual && status.manualMinutes > 0 -> minutesText(this, status.manualMinutes)
            else -> getString(if (active) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, WakeTileService::class.java)) }
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
 * "Allow other apps" is off, and the setting is checked again for every broadcast. The only extra read is
 * `minutes` on ON and TOGGLE (a whole number, 0 to 1440); every other extra is ignored.
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
                    val minutes = if (action == WakeService.ACTION_OFF) null else minutesExtra(intent)
                    if (minutes == INVALID) ActivityLog.add(context.getString(R.string.log_automation_bad_minutes))
                    WakeService.sendFromBackground(context, action, context.getString(R.string.log_source_automation), minutes?.takeIf { it != INVALID } ?: -1)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val INVALID = -2

        /**
         * The `minutes` extra: null when there is none, [INVALID] when it isn't a whole number from 0 to 1440 (the
         * request then uses the timer setting). Read with care: the extras come from another app.
         */
        private fun minutesExtra(intent: Intent): Int? = runCatching {
            val extras = intent.extras ?: return@runCatching null
            if (!extras.containsKey(WakeService.EXTRA_MINUTES)) return@runCatching null
            @Suppress("DEPRECATION")
            TimerRule.parseExtra(extras.get(WakeService.EXTRA_MINUTES)) ?: INVALID
        }.getOrDefault(INVALID)

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
                    resume -> WakeService.sendFromBackground(context, WakeService.ACTION_RESUME)
                    s.anyAutomation -> WakeService.sendFromBackground(context, WakeService.ACTION_REFRESH)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
