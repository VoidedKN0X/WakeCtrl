package com.vknxworks.wakectrl.widget

import android.app.Activity
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
import com.vknxworks.wakectrl.wake.WakeService
import com.vknxworks.wakectrl.wake.WakeState
import kotlinx.coroutines.launch

/** Quick Settings tile: tap to keep the screen on, tap again to stop. */
class WakeTileService : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        val on = WakeState.status.value.manual
        val ok = WakeService.send(this, if (on) WakeService.ACTION_OFF else WakeService.ACTION_ON, getString(R.string.log_source_tile))
        if (!ok) Toast.makeText(this, getString(R.string.start_blocked), Toast.LENGTH_LONG).show()
    }

    private fun update() {
        val tile = qsTile ?: return
        val st = WakeState.status.value
        tile.state = if (st.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = getString(if (st.active) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, WakeTileService::class.java)) }
        }
    }
}

/** Home-screen widget: one tap toggles keeping the screen on. */
class WakeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            WakeService.send(context, WakeService.ACTION_TOGGLE, context.getString(R.string.log_source_widget))
        }
    }

    companion object {
        private const val ACTION_WIDGET_TOGGLE = "com.vknxworks.wakectrl.action.WIDGET_TOGGLE"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WakeWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val on = WakeState.status.value.active
            val tap = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, WakeWidget::class.java).setAction(ACTION_WIDGET_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE,
            )
            for (id in ids) {
                val v = RemoteViews(context.packageName, R.layout.widget_wake)
                v.setTextViewText(R.id.widget_state, context.getString(if (on) R.string.tile_on else R.string.tile_off))
                v.setImageViewResource(R.id.widget_icon, if (on) R.drawable.ic_widget_on else R.drawable.ic_widget_off)
                v.setOnClickPendingIntent(R.id.widget_root, tap)
                manager.updateAppWidget(id, v)
            }
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

/** After a restart: resume a manual keep-awake (if set) and start watching for automations. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                val s = c.settings.settingsLoaded()
                val resume = s.resumeAfterRestart && c.settings.wasManualOnNow()
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
