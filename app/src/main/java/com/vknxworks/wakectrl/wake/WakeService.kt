package com.vknxworks.wakectrl.wake

import android.app.KeyguardManager
import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.vknxworks.wakectrl.MainActivity
import com.vknxworks.wakectrl.R
import com.vknxworks.wakectrl.container
import com.vknxworks.wakectrl.data.settings.AppSettings
import com.vknxworks.wakectrl.data.settings.Brightness
import com.vknxworks.wakectrl.ui.common.hasPermission
import com.vknxworks.wakectrl.widget.WakeTileService
import com.vknxworks.wakectrl.widget.WakeWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Keeps the screen on, manually or when an automation matches, and watches for those automations
 * (app on screen, charging, Bluetooth device, debug build). Runs as a foreground service, only while
 * WakeCtrl is holding the screen or an automation is switched on. Nothing here touches the network.
 */
class WakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    private var manualOn = false
    private var manualUntil = 0L
    private var stoodDown = false
    private var lowBatteryOverride = false
    private var batteryLevel = 100
    private var charging = false
    private val btConnected = mutableSetOf<String>()
    private var foregroundPkg: String? = null
    private var lastEventQuery = 0L
    private var activeSinceOnBattery = 0L
    private var reminded = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var holding: Method? = null
    private var renewJob: kotlinx.coroutines.Job? = null
    private var lastNotifText: String? = null
    private var settingsReady = false
    /** While a Schedule window holds the screen: when it ends (epoch ms). */
    private var scheduleUntil = 0L
    private var wasScheduled = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readBattery(intent)
    }
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> btConnected += device.address
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> btConnected -= device.address
            }
            evaluate()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ActivityLog.init(this)
        createChannels(this)
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { readBattery(it) }
        ContextCompat.registerReceiver(
            this, btReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            // System broadcasts still arrive; other apps can't fake them.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        loadConnectedBluetooth()
        WakeState.set { it.copy(running = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(lastNotifText ?: getString(R.string.notif_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val action = intent?.action
        val source = intent?.getStringExtra(EXTRA_SOURCE)
        scope.launch {
            // After a cold start (boot, widget, tile) the settings may still be loading: read them first.
            val s = container.settings.settingsLoaded()
            settingsReady = true
            when (action) {
                ACTION_ON -> setManual(true, s, source)
                ACTION_OFF -> setManual(false, s, source)
                ACTION_TOGGLE -> setManual(!manualOn, s, source)
                ACTION_RESUME -> setManual(true, s, getString(R.string.log_source_restart))
            }
            if (loop?.isActive != true) {
                loop = scope.launch {
                    while (isActive) {
                        evaluate()
                        delay(if (needsPolling()) 2_000L else 15_000L)
                    }
                }
            } else {
                evaluate()
            }
        }
        return START_STICKY
    }

    private fun setManual(on: Boolean, s: AppSettings, source: String?) {
        if (on && stoodDown) {
            // Turning it on yourself after a low-battery stop overrides it until the phone charges.
            stoodDown = false
            lowBatteryOverride = true
        }
        if (!on) lowBatteryOverride = false
        manualOn = on
        manualUntil = if (on && s.timer.minutes > 0) System.currentTimeMillis() + s.timer.minutes * 60_000L else 0L
        container.appScope.launch { container.settings.setManualOn(on) }
        ActivityLog.add(getString(if (on) R.string.log_manual_on else R.string.log_manual_off, source ?: getString(R.string.log_source_app)))
    }

    private fun needsPolling(): Boolean {
        val s = container.settings.settings.value
        return (s.appWake && s.appWakePackages.isNotEmpty()) || s.devWake || manualUntil > 0 || holding != null
    }

    /** Works out whether to hold the screen, and why; applies it; stops when there's nothing to do. */
    private fun evaluate() {
        if (!settingsReady) return
        val s = container.settings.settings.value
        val now = System.currentTimeMillis()

        if (manualOn && manualUntil in 1..now) {
            manualOn = false
            manualUntil = 0
            container.appScope.launch { container.settings.setManualOn(false) }
            ActivityLog.add(getString(R.string.log_timer_done))
        }
        if (charging) {
            stoodDown = false
            lowBatteryOverride = false
        }

        val pm = getSystemService(PowerManager::class.java)
        val interactive = pm.isInteractive
        if (interactive && ((s.appWake && s.appWakePackages.isNotEmpty()) || s.devWake)) updateForegroundApp()

        val reasons = mutableSetOf<Reason>()
        if (manualOn) reasons += Reason.MANUAL
        var trigger: String? = null
        if (s.chargeWake && charging) reasons += Reason.CHARGING
        if (s.btWake && btConnected.any { it in s.btWakeDevices }) reasons += Reason.BLUETOOTH
        // Schedule: inside one of the set windows (worked out on the wall clock, so DST and time zone changes
        // apply straight away), and an alarm for the next start or end so it switches on time.
        val zNow = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
        var scheduleUntil = 0L
        if (s.scheduleWake && ScheduleMath.isInside(s.scheduleWindows, zNow)) {
            reasons += Reason.SCHEDULE
            scheduleUntil = ScheduleMath.nextChange(s.scheduleWindows, zNow)?.toInstant()?.toEpochMilli() ?: 0L
        }
        this.scheduleUntil = scheduleUntil
        val scheduled = Reason.SCHEDULE in reasons
        if (scheduled != wasScheduled) {
            wasScheduled = scheduled
            ActivityLog.add(
                if (scheduled && scheduleUntil > 0) getString(R.string.log_schedule_start, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(scheduleUntil)))
                else getString(if (scheduled) R.string.log_schedule_start_open else R.string.log_schedule_end),
            )
        }
        ScheduleAlarm.update(this, s, zNow)
        val fg = foregroundPkg.takeIf { interactive }
        if (fg != null && fg != packageName) {
            // Apps set to "Only while charging" count only while the phone charges (a plug change re-evaluates).
            if (AppWakeRule.keepsOn(fg, s.appWake, s.appWakePackages, s.appWakeChargingOnly, charging)) { reasons += Reason.APP; trigger = fg }
            if (s.devWake && isDebuggable(fg)) { reasons += Reason.DEBUG_APP; trigger = fg }
        }

        // Low battery: let go, and leave automations alone until the phone charges.
        val threshold = s.lowBattery.percent
        if (lowBatteryOverride) {
            // You turned it back on after a low-battery stop: only your manual keep-awake runs.
            reasons.retainAll(setOf(Reason.MANUAL))
        } else if (threshold > 0 && !charging && batteryLevel <= threshold && (reasons.isNotEmpty() || stoodDown)) {
            if (!stoodDown) {
                stoodDown = true
                manualOn = false
                manualUntil = 0
                container.appScope.launch { container.settings.setManualOn(false) }
                ActivityLog.add(getString(R.string.log_low_battery, batteryLevel))
                alert(ALERT_LOW_BATTERY, getString(R.string.alert_low_battery_title), getString(R.string.alert_low_battery_body, threshold))
            }
            reasons.clear()
        }

        val wasActive = holding != null
        if (reasons.isNotEmpty()) hold(s) else release()
        if (!wasActive && reasons.isNotEmpty() && Reason.MANUAL !in reasons) {
            ActivityLog.add(getString(R.string.log_auto_on, reasons.joinToString { reasonText(it) }))
        } else if (wasActive && reasons.isEmpty()) {
            ActivityLog.add(getString(R.string.log_released))
        }

        // Long-session reminder: 3 hours held on battery.
        if (reasons.isNotEmpty() && !charging) {
            if (activeSinceOnBattery == 0L) activeSinceOnBattery = now
            if (s.longReminder && !reminded && now - activeSinceOnBattery >= LONG_SESSION_MS) {
                reminded = true
                alert(ALERT_LONG, getString(R.string.alert_long_title), getString(R.string.alert_long_body))
            }
        } else {
            activeSinceOnBattery = 0L
            reminded = false
        }

        WakeState.set {
            it.copy(
                running = true, reasons = reasons, manualUntil = if (manualOn) manualUntil else 0L, method = holding, stoodDown = stoodDown, triggerApp = trigger,
                scheduleUntil = if (Reason.SCHEDULE in reasons) scheduleUntil else 0L,
            )
        }
        updateNotification(reasons)
        WakeTileService.refresh(this)
        WakeWidget.refresh(this)

        // After a low-battery stop, stay (with a notification saying so) until the phone charges, so turning it
        // on again counts as overriding the stop.
        if (reasons.isEmpty() && !s.anyAutomation && !stoodDown) stopSelf()
    }

    // ---------- Holding the screen ----------

    private fun hold(s: AppSettings) {
        val want = if (s.allowDim) Method.WAKE_LOCK_DIM else Method.WAKE_LOCK_BRIGHT
        if (holding == want) return
        release(restoreBrightness = false)
        when (want) {
            Method.WAKE_LOCK_DIM -> acquireWakeLock(dim = true)
            Method.WAKE_LOCK_BRIGHT -> acquireWakeLock(dim = false)
        }
        holding = want
        applyBrightness(s.brightness)
    }

    private fun release(restoreBrightness: Boolean = true) {
        renewJob?.cancel()
        renewJob = null
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        if (holding != null && restoreBrightness) restoreBrightness()
        holding = null
    }

    /**
     * Holds the screen with short, timed screen wake locks that are renewed every few seconds (a fresh lock is taken
     * before the old one is let go). Some phones (ColorOS among them) drop a screen wake lock that a background app
     * holds for a long time; a renewed lock keeps working there, without an overlay or changing system settings.
     * Renewal only happens while the screen is on and the phone is unlocked, so turning the screen off yourself
     * (power button) still works and WakeCtrl never wakes a locked phone.
     */
    private fun acquireWakeLock(dim: Boolean) {
        renewJob?.cancel()
        takeScreenLock(dim, wake = false)
        renewJob = scope.launch {
            while (isActive) {
                delay(RENEW_MS)
                val pm = getSystemService(PowerManager::class.java)
                val km = getSystemService(KeyguardManager::class.java)
                if (pm.isInteractive && !km.isDeviceLocked) takeScreenLock(dim, wake = true)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun takeScreenLock(dim: Boolean, wake: Boolean) {
        val level = (if (dim) PowerManager.SCREEN_DIM_WAKE_LOCK else PowerManager.SCREEN_BRIGHT_WAKE_LOCK) or
            PowerManager.ON_AFTER_RELEASE or (if (wake) PowerManager.ACQUIRE_CAUSES_WAKEUP else 0)
        val fresh = getSystemService(PowerManager::class.java).newWakeLock(level, "WakeCtrl:screen").apply {
            setReferenceCounted(false)
            acquire(LOCK_MS)
        }
        val old = wakeLock
        wakeLock = fresh
        old?.let { if (it.isHeld) runCatching { it.release() } }
    }

    // ---------- Brightness ----------


    private fun applyBrightness(b: Brightness) {
        if (b == Brightness.UNCHANGED || !Settings.System.canWrite(this)) return
        val prefs = getSharedPreferences("brightness", MODE_PRIVATE)
        val cr = contentResolver
        if (!prefs.contains("level")) {
            prefs.edit()
                .putInt("level", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
                .putInt("mode", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC))
                .apply()
        }
        runCatching {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (b.percent * 255 / 100).coerceIn(1, 255))
        }
    }

    private fun restoreBrightness() {
        val prefs = getSharedPreferences("brightness", MODE_PRIVATE)
        if (!prefs.contains("level") || !Settings.System.canWrite(this)) return
        runCatching {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, prefs.getInt("level", 128))
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, prefs.getInt("mode", Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC))
        }
        prefs.edit().clear().apply()
    }

    // ---------- Watching ----------

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val was = charging
        charging = plugged != 0
        if (was != charging && loop != null) evaluate()
    }

    private fun updateForegroundApp() {
        if (!hasUsageAccess(this)) return
        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val from = if (lastEventQuery == 0L) now - 60_000 else lastEventQuery - 1_000
        lastEventQuery = now
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foregroundPkg = e.packageName
        }
    }

    private fun isDebuggable(pkg: String): Boolean = runCatching {
        packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }.getOrDefault(false)

    private fun loadConnectedBluetooth() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP, BluetoothProfile.LE_AUDIO)) {
            runCatching {
                adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices.forEach { btConnected += it.address } }
                        runCatching { adapter.closeProfileProxy(p, proxy) }
                        evaluate()
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            }
        }
    }

    // ---------- Notifications ----------

    private fun reasonText(r: Reason): String {
        if (r == Reason.SCHEDULE && scheduleUntil > 0) {
            return getString(R.string.reason_schedule_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(scheduleUntil)))
        }
        return getString(
            when (r) {
                Reason.MANUAL -> R.string.reason_manual
                Reason.APP -> R.string.reason_app
                Reason.CHARGING -> R.string.reason_charging
                Reason.BLUETOOTH -> R.string.reason_bluetooth
                Reason.DEBUG_APP -> R.string.reason_debug
                Reason.SCHEDULE -> R.string.reason_schedule
            },
        )
    }

    private fun updateNotification(reasons: Set<Reason>) {
        val text = when {
            stoodDown -> getString(R.string.notif_stood_down)
            reasons.isEmpty() -> getString(R.string.notif_watching)
            manualOn && manualUntil > 0 -> getString(R.string.notif_on_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(manualUntil)))
            else -> getString(R.string.notif_on_because, reasons.joinToString { reasonText(it) })
        }
        if (text == lastNotifText) return
        lastNotifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, WakeService::class.java).setAction(if (manualOn) ACTION_OFF else ACTION_ON).putExtra(EXTRA_SOURCE, getString(R.string.log_source_notification)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_wake)
            .setContentTitle(getString(if (holding != null) R.string.notif_title_on else R.string.notif_title_watching))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(if (manualOn) R.string.turn_off else R.string.turn_on), toggle).build())
            .build()
    }

    private fun alert(id: Int, title: String, body: String) {
        if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(
            id,
            Notification.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_wake)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun onDestroy() {
        release()
        // With Schedule on, its alarm stays set so the next window starts WakeCtrl again.
        if (!container.settings.settings.value.scheduleWake) ScheduleAlarm.cancel(this)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(btReceiver) }
        scope.cancel()
        WakeState.set { WakeStatus() }
        WakeTileService.refresh(this)
        WakeWidget.refresh(this)
        super.onDestroy()
    }

    companion object {
        /** A fresh screen lock every 10 seconds; each one times out after 20 seconds on its own as a safety net. */
        private const val RENEW_MS = 10_000L
        private const val LOCK_MS = 20_000L

        const val ACTION_ON = "com.vknxworks.wakectrl.action.ON"
        const val ACTION_OFF = "com.vknxworks.wakectrl.action.OFF"
        const val ACTION_TOGGLE = "com.vknxworks.wakectrl.action.TOGGLE"
        const val ACTION_REFRESH = "com.vknxworks.wakectrl.action.REFRESH"
        const val ACTION_RESUME = "com.vknxworks.wakectrl.action.RESUME"
        const val EXTRA_SOURCE = "source"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIF_ID = 1
        private const val ALERT_LOW_BATTERY = 2
        private const val ALERT_LONG = 3
        private const val LONG_SESSION_MS = 3 * 60 * 60 * 1000L

        /** Starts the service with an action. False when Android doesn't allow it right now. */
        fun send(context: Context, action: String, source: String? = null): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java).setAction(action).putExtra(EXTRA_SOURCE, source))
            true
        } catch (_: Exception) {
            false
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, context.getString(R.string.ch_status), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.ch_status_desc); setShowBadge(false) },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.ch_alerts_desc) },
            )
        }

        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }
    }
}
