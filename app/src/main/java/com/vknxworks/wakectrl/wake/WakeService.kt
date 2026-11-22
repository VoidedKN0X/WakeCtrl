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
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Keeps the screen on, manually or when an automation matches, and watches for those automations
 * (app on screen, charging, Bluetooth device, schedule, media, dock, headset). Runs as a foreground service, only while
 * WakeCtrl is holding the screen or an automation is switched on. Nothing here touches the network.
 */
class WakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    private var manualOn = false
    private var manualUntil = 0L
    /** The length the manual keep-awake was started with (0: no limit). */
    private var manualMinutes = 0
    /** Whether the screen was being kept on at the last check (for the activity log). */
    private var lastActive = false
    // "Stop when the battery is hot": the last temperature (tenths of °C), whether it counts as hot, whether
    // WakeCtrl stood down for it, and whether you turned it back on yourself since.
    private var batteryTenths: Int? = null
    private var hot = false
    private var heatStopped = false
    private var heatOverride = false
    // MediaWake, DockWake, HeadsetWake.
    private var docked = false
    private var headsetConnected = false
    // Pocket guard.
    private var pocketCovered = false
    private var pocketJob: Job? = null
    private var proximityOn = false
    // AppWake time limits: the app on screen and since when.
    private var appTimerPkg: String? = null
    private var appTimerSince = 0L
    private var appTimerEnded = false
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
    /** What the Quick Settings tile was last told; it's only asked to refresh when this changes. */
    private var tileActive: Boolean? = null
    /** Whether a brightness level left behind by a closed WakeCtrl process was looked for (once per start). */
    private var leftoverBrightnessChecked = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readBattery(intent)
    }
    private val dockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readDock(intent)
            evaluate()
        }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = readHeadset()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = readHeadset()
    }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = evaluate()
    }
    private val proximity = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values.firstOrNull() ?: return
            // Most proximity sensors only say "near" (0) or "far" (their maximum range); others give centimetres.
            val near = v < minOf(event.sensor.maximumRange, 5f)
            if (near) {
                if (!pocketCovered && pocketJob == null) {
                    // Covered for a few seconds, not just a hand passing over the top of the phone.
                    pocketJob = scope.launch {
                        delay(POCKET_DELAY_MS)
                        pocketJob = null
                        pocketCovered = true
                        ActivityLog.add(getString(R.string.log_pocket_paused))
                        evaluate()
                    }
                }
            } else {
                pocketJob?.cancel()
                pocketJob = null
                if (pocketCovered) {
                    pocketCovered = false
                    ActivityLog.add(getString(R.string.log_pocket_resumed))
                    evaluate()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
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

    /**
     * Screen off (power button or timeout): with "Turn off when the screen goes off", end the manual keep-awake.
     * Screen on: look straight away and go back to the quick checks (they slow down while the screen is off).
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                if (settingsReady && loop?.isActive == true) {
                    loop?.cancel()
                    loop = startLoop()
                }
                return
            }
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            // The pocket guard only listens while the screen is on.
            stopPocketSensor()
            if (!settingsReady) return
            val s = container.settings.settings.value
            if (s.offWhenScreenOff && manualOn) {
                setManual(false, s, getString(R.string.log_source_screen_off))
                evaluate()
            }
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
        // SCREEN_OFF and SCREEN_ON only reach receivers registered at runtime; they're protected system broadcasts.
        ContextCompat.registerReceiver(
            this, screenOffReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Docked or not: a sticky system broadcast, so registering also gives the current state.
        ContextCompat.registerReceiver(this, dockReceiver, IntentFilter(Intent.ACTION_DOCK_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { readDock(it) }
        getSystemService(AudioManager::class.java)?.let { audio ->
            // Both callbacks need no permission. The device callback reports the devices already connected right away.
            runCatching { audio.registerAudioDeviceCallback(audioDevices, mainHandler) }
            runCatching { audio.registerAudioPlaybackCallback(playback, mainHandler) }
        }
        loadConnectedBluetooth()
        WakeState.set { it.copy(running = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(lastNotifText ?: getString(R.string.notif_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val action = intent?.action
        val source = intent?.getStringExtra(EXTRA_SOURCE)
        // Only WakeCtrl itself starts this service (it isn't exported); -1 means "use the timer setting".
        val minutes = intent?.getIntExtra(EXTRA_MINUTES, -1) ?: -1
        scope.launch {
            // After a cold start (boot, tile) the settings may still be loading: read them first.
            val s = container.settings.settingsLoaded()
            settingsReady = true
            when (action) {
                ACTION_ON -> setManual(true, s, source, minutes)
                ACTION_OFF -> setManual(false, s, source)
                ACTION_TOGGLE -> setManual(!manualOn, s, source, minutes)
                ACTION_TILE -> if (s.tileCycle) {
                    // Tap to cycle: off → first length → next … → off.
                    val next = TimerRule.nextStep(if (manualOn) manualMinutes else null, s.tileSteps)
                    if (next == null) setManual(false, s, source) else setManual(true, s, source, next)
                } else {
                    setManual(!manualOn, s, source)
                }
                ACTION_RESUME -> setManual(true, s, getString(R.string.log_source_restart))
                // No intent: Android restarted the service after stopping it (START_STICKY). A manual keep-awake
                // lived only in memory, so carry it on from what was saved, if its time hasn't run out.
                null -> if (intent == null) resumeAfterServiceRestart(s)
            }
            // Look now, and work the wait until the next look out afresh (a timer may just have been set, or
            // AppWake switched on).
            loop?.cancel()
            loop = startLoop()
        }
        return START_STICKY
    }

    /**
     * Looks at the automations again and again: every 2 seconds while the screen is on and AppWake
     * has to notice the app on screen changing, every 15 seconds otherwise, and in any case right when the timer
     * or the current schedule window ends ([PollRule]). Charging, Bluetooth, the schedule alarm and settings
     * changes come in by themselves; turning the screen on starts this again at once.
     */
    private fun startLoop(): Job = scope.launch {
        while (isActive) {
            refreshForegroundApp()
            evaluate()
            delay(
                PollRule.delayMs(
                    watchingApps = watchesApps(),
                    screenOn = getSystemService(PowerManager::class.java).isInteractive,
                    now = System.currentTimeMillis(),
                    manualUntil = if (manualOn) manualUntil else 0L,
                    scheduleUntil = scheduleUntil,
                ),
            )
        }
    }

    /** After Android restarted the service: carries on a saved manual keep-awake, or forgets one that has run out. */
    private suspend fun resumeAfterServiceRestart(s: AppSettings) {
        val (on, until, minutes) = container.settings.savedManual()
        if (!on) return
        val now = System.currentTimeMillis()
        if (minutes != null && ManualResumeRule.stillRuns(on, until, now)) {
            setManual(true, s, getString(R.string.log_source_service_restart), minutes, resumeUntil = until)
        } else {
            // Run out while WakeCtrl was stopped (or saved by an older version without its length): forget it, so
            // a later restart of the phone doesn't bring it back either.
            container.appScope.launch { container.settings.setManualOn(false) }
            if (minutes != null) ActivityLog.add(getString(R.string.log_timer_done))
        }
    }

    /**
     * [minutes]: how long to keep it on (0: no limit), or -1 for the timer setting. [resumeUntil]: carrying on a
     * keep-awake after a service restart, the time it ends (epoch ms); [minutes] is then the length it started with.
     */
    private fun setManual(on: Boolean, s: AppSettings, source: String?, minutes: Int = -1, resumeUntil: Long = 0L) {
        if (on && stoodDown) {
            // Turning it on yourself after a low-battery stop overrides it until the phone charges.
            stoodDown = false
            lowBatteryOverride = true
        }
        if (on && heatStopped) {
            // Likewise after a hot-battery stop, until the battery has cooled down.
            heatStopped = false
            heatOverride = true
        }
        if (!on) {
            lowBatteryOverride = false
            heatOverride = false
        }
        val length = if (minutes >= 0) minutes.coerceAtMost(TimerRule.MAX_MINUTES) else s.timerMinutes
        val now = System.currentTimeMillis()
        manualOn = on
        manualMinutes = if (on) length else 0
        manualUntil = when {
            !on || length <= 0 -> 0L
            resumeUntil > 0L -> resumeUntil
            else -> now + length * 60_000L
        }
        val until = manualUntil
        val savedMinutes = manualMinutes
        container.appScope.launch { container.settings.setManualOn(on, until, savedMinutes) }
        val from = source ?: getString(R.string.log_source_app)
        // For a keep-awake carried on after a restart, the log shows the time that is left.
        val shown = if (on && until > 0L && resumeUntil > 0L) ManualResumeRule.minutesLeft(until, now) else length
        ActivityLog.add(
            when {
                !on -> getString(R.string.log_manual_off, from)
                length > 0 -> getString(R.string.log_manual_on_for, minutesText(this, shown), from)
                else -> getString(R.string.log_manual_on, from)
            },
        )
    }

    /** AppWake is on with apps chosen: the app on screen has to be watched closely. */
    private fun watchesApps(): Boolean {
        val s = container.settings.settings.value
        return s.appWake && s.appWakePackages.isNotEmpty()
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
        // The app on screen is looked up by the poll loop ([refreshForegroundApp]), off the main thread.

        val reasons = mutableSetOf<Reason>()
        if (manualOn) reasons += Reason.MANUAL
        var trigger: String? = null
        if (s.chargeWake && charging) reasons += Reason.CHARGING
        if (s.btWake && btConnected.any { it in s.btWakeDevices }) reasons += Reason.BLUETOOTH
        if (s.mediaWake && isMediaPlaying()) reasons += Reason.MEDIA
        if (s.dockWake && docked) reasons += Reason.DOCK
        if (s.headsetWake && headsetConnected) reasons += Reason.HEADSET
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
        // An app's time limit counts from when it came on screen (or the screen came on with it).
        if (fg != appTimerPkg) {
            appTimerPkg = fg
            appTimerSince = now
            appTimerEnded = false
        }
        if (fg != null && fg != packageName) {
            // Apps set to "Only while charging" count only while the phone charges (a plug change re-evaluates).
            if (AppWakeRule.keepsOn(fg, s.appWake, s.appWakePackages, s.appWakeChargingOnly, charging)) {
                val limit = s.appWakeTimers[fg] ?: 0
                if (!AppTimerRule.expired(limit, appTimerSince, now)) {
                    reasons += Reason.APP
                    trigger = fg
                } else if (!appTimerEnded) {
                    appTimerEnded = true
                    ActivityLog.add(getString(R.string.log_app_timer_done, labelOf(fg), minutesText(this, limit)))
                }
            }
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

        // Hot battery: let go, and leave automations alone until it has cooled down.
        val nowHot = HeatRule.isHot(hot, batteryTenths, s.heatStop.celsius)
        if (hot && !nowHot && heatStopped) ActivityLog.add(getString(R.string.log_heat_cooled))
        hot = nowHot
        if (!hot) {
            heatStopped = false
            heatOverride = false
        } else if (heatOverride) {
            // You turned it back on after a hot-battery stop: only your manual keep-awake runs.
            reasons.retainAll(setOf(Reason.MANUAL))
        } else {
            if (reasons.isNotEmpty() && !heatStopped) {
                heatStopped = true
                manualOn = false
                manualUntil = 0
                container.appScope.launch { container.settings.setManualOn(false) }
                val celsius = formatCelsius(batteryTenths ?: 0)
                ActivityLog.add(getString(R.string.log_heat_stop, celsius))
                alert(ALERT_HEAT, getString(R.string.alert_heat_title), getString(R.string.alert_heat_body, celsius))
            }
            if (heatStopped) reasons.clear()
        }

        // Pocket guard: listen to the proximity sensor only while something keeps the screen on and it's on.
        val wantPocket = s.pocketGuard && reasons.isNotEmpty() && interactive
        if (wantPocket) startPocketSensor() else stopPocketSensor()
        val pocketPaused = pocketCovered && reasons.isNotEmpty()

        val wasActive = lastActive
        lastActive = reasons.isNotEmpty()
        if (reasons.isNotEmpty() && !pocketPaused) hold(s) else release()
        if (!leftoverBrightnessChecked) {
            leftoverBrightnessChecked = true
            // WakeCtrl was closed by Android while it had the brightness changed: put the saved level back now
            // that nothing holds the screen. (When something does, the saved level is put back when it lets go.)
            if (!wasActive && reasons.isEmpty()) restoreBrightness()
        }
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
                manualMinutes = if (manualOn) manualMinutes else 0, pocketPaused = pocketPaused, heatStopped = heatStopped,
                batteryTenths = batteryTenths,
            )
        }
        updateNotification(reasons, pocketPaused)
        // The tile only shows on or off: ask it to refresh when that changed, not on every check.
        val active = reasons.isNotEmpty()
        if (tileActive != active) {
            tileActive = active
            WakeTileService.refresh(this)
        }

        // After a low-battery stop, stay (with a notification saying so) until the phone charges, so turning it
        // on again counts as overriding the stop.
        if (reasons.isEmpty() && !s.anyAutomation && !stoodDown && !heatStopped) stopSelf()
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
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths != Int.MIN_VALUE) batteryTenths = tenths
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val was = charging
        charging = plugged != 0
        // Look again straight away when the battery crosses the "hot" line either way.
        val heatFlip = settingsReady && HeatRule.isHot(hot, batteryTenths, container.settings.settings.value.heatStop.celsius) != hot
        if ((was != charging || heatFlip) && loop != null) evaluate()
    }

    /**
     * Looks up which app is on screen, for AppWake: only with the screen on, AppWake on and apps chosen. The
     * usage-events query (a call into the system and a list of events) runs off the main thread, which also draws
     * WakeCtrl's screens; [foregroundPkg] and [lastEventQuery] are only changed back on the main thread.
     */
    private suspend fun refreshForegroundApp() {
        if (!settingsReady) return
        val s = container.settings.settings.value
        if (!s.appWake || s.appWakePackages.isEmpty()) return
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return
        if (!hasUsageAccess(this)) return
        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val from = if (lastEventQuery == 0L) now - 60_000 else lastEventQuery - 1_000
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val events = usm.queryEvents(from, now)
                val e = UsageEvents.Event()
                var latest: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = e.packageName
                }
                latest
            }
        }
        // A failed query is tried again next time, from the same point.
        val pkg = result.getOrElse { return }
        lastEventQuery = now
        if (pkg != null) foregroundPkg = pkg
    }

    private fun readDock(intent: Intent) {
        docked = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED) != Intent.EXTRA_DOCK_STATE_UNDOCKED
    }

    /** Headphones or a headset: wired, USB, or Bluetooth audio. */
    private fun readHeadset() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val now = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES } }.getOrDefault(false)
        if (now != headsetConnected) {
            headsetConnected = now
            evaluate()
        }
    }

    private fun isMediaPlaying(): Boolean = runCatching { getSystemService(AudioManager::class.java)?.isMusicActive == true }.getOrDefault(false)

    private fun startPocketSensor() {
        if (proximityOn) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, false) ?: sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        proximityOn = runCatching { sm.registerListener(proximity, sensor, SensorManager.SENSOR_DELAY_NORMAL) }.getOrDefault(false)
    }

    private fun stopPocketSensor() {
        pocketJob?.cancel()
        pocketJob = null
        pocketCovered = false
        if (!proximityOn) return
        proximityOn = false
        runCatching { getSystemService(SensorManager::class.java)?.unregisterListener(proximity) }
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

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
                Reason.SCHEDULE -> R.string.reason_schedule
                Reason.MEDIA -> R.string.reason_media
                Reason.DOCK -> R.string.reason_dock
                Reason.HEADSET -> R.string.reason_headset
            },
        )
    }

    private fun updateNotification(reasons: Set<Reason>, pocketPaused: Boolean) {
        val text = when {
            stoodDown -> getString(R.string.notif_stood_down)
            heatStopped -> getString(R.string.notif_heat)
            pocketPaused -> getString(R.string.notif_pocket)
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
        // Only once the settings are known: their placeholder defaults say Schedule is off.
        if (settingsReady && !container.settings.settings.value.scheduleWake) ScheduleAlarm.cancel(this)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(btReceiver) }
        runCatching { unregisterReceiver(dockReceiver) }
        getSystemService(AudioManager::class.java)?.let { audio ->
            runCatching { audio.unregisterAudioDeviceCallback(audioDevices) }
            runCatching { audio.unregisterAudioPlaybackCallback(playback) }
        }
        stopPocketSensor()
        runCatching { unregisterReceiver(screenOffReceiver) }
        scope.cancel()
        WakeState.set { WakeStatus() }
        WakeTileService.refresh(this)
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
        /** A tap on the Quick Settings tile: a plain toggle, or the next timer length with "Tap to cycle". */
        const val ACTION_TILE = "com.vknxworks.wakectrl.action.TILE"
        const val EXTRA_SOURCE = "source"
        /** How long to keep the screen on, in minutes (0: no limit); WakeCtrl's own intents only. */
        const val EXTRA_MINUTES = "minutes"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIF_ID = 1
        private const val ALERT_LOW_BATTERY = 2
        private const val ALERT_LONG = 3
        private const val ALERT_HEAT = 4
        private const val ALERT_START_BLOCKED = 5
        /** The proximity sensor has to stay covered this long before the pocket guard lets the screen sleep. */
        private const val POCKET_DELAY_MS = 5_000L
        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        private const val LONG_SESSION_MS = 3 * 60 * 60 * 1000L

        /** Starts the service with an action. False when Android doesn't allow it right now. */
        fun send(context: Context, action: String, source: String? = null, minutes: Int = -1): Boolean = try {
            val intent = Intent(context, WakeService::class.java).setAction(action).putExtra(EXTRA_SOURCE, source)
            if (minutes >= 0) intent.putExtra(EXTRA_MINUTES, minutes)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (_: Exception) {
            false
        }

        /**
         * [send] from a receiver: another app's request, a restart of the phone or the Schedule alarm. When the
         * service isn't running and WakeCtrl's battery use isn't Unrestricted, Android can refuse to start it from
         * the background; the request is then lost, so it's written to the activity log and a notification says
         * how to fix it, instead of nothing happening without a trace.
         */
        fun sendFromBackground(context: Context, action: String, source: String? = null, minutes: Int = -1): Boolean {
            if (send(context, action, source, minutes)) return true
            ActivityLog.init(context)
            ActivityLog.add(context.getString(R.string.log_start_blocked))
            runCatching {
                if (!context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return@runCatching
                createChannels(context)
                val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                val body = context.getString(R.string.alert_start_blocked_body)
                context.getSystemService(NotificationManager::class.java).notify(
                    ALERT_START_BLOCKED,
                    Notification.Builder(context, CHANNEL_ALERTS)
                        .setSmallIcon(R.drawable.ic_stat_wake)
                        .setContentTitle(context.getString(R.string.alert_start_blocked_title))
                        .setContentText(body)
                        .setStyle(Notification.BigTextStyle().bigText(body))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build(),
                )
            }
            return false
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
