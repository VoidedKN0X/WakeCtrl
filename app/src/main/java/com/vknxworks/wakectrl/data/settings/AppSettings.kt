package com.vknxworks.wakectrl.data.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.wakectrl.R
import com.vknxworks.wakectrl.wake.AppTimerRule
import com.vknxworks.wakectrl.wake.TimerRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "wakectrl")

/** How long WakeCtrl can be in the background before the app lock asks again (-1: only after a restart). */
enum class LockTimeout(@StringRes val label: Int, val millis: Long) {
    IMMEDIATELY(R.string.lock_again_immediately, 0L),
    MINUTE_1(R.string.lock_again_1m, 60_000L),
    MINUTES_5(R.string.lock_again_5m, 5 * 60_000L),
    MINUTES_15(R.string.lock_again_15m, 15 * 60_000L),
    HOUR_1(R.string.lock_again_1h, 60 * 60_000L),
    RESTART(R.string.lock_again_restart, -1L),
}

/** How long a manual "keep awake" lasts. */
enum class WakeTimer(@StringRes val label: Int, val minutes: Int) {
    NONE(R.string.timer_none, 0),
    M5(R.string.timer_5, 5),
    M15(R.string.timer_15, 15),
    M30(R.string.timer_30, 30),
    H1(R.string.timer_60, 60),
    H2(R.string.timer_120, 120),
    /** Your own length ([AppSettings.customTimerMinutes]). */
    CUSTOM(R.string.timer_custom, -1),
}

/** Stop keeping the screen on when the battery gets this hot (and let automations wait until it cools down). */
enum class HeatStop(@StringRes val label: Int, val celsius: Int) {
    OFF(R.string.heat_off, 0),
    C40(R.string.heat_40, 40),
    C42(R.string.heat_42, 42),
    C45(R.string.heat_45, 45),
}

/** Stop keeping the screen on when the battery falls to this level (and the phone isn't charging). */
enum class LowBattery(@StringRes val label: Int, val percent: Int) {
    OFF(R.string.low_battery_off, 0),
    P10(R.string.low_battery_10, 10),
    P15(R.string.low_battery_15, 15),
    P20(R.string.low_battery_20, 20),
    P30(R.string.low_battery_30, 30),
}

/** Screen brightness while WakeCtrl keeps it on. */
enum class Brightness(@StringRes val label: Int, val percent: Int) {
    UNCHANGED(R.string.brightness_unchanged, -1),
    LOW(R.string.brightness_low, 10),
    MEDIUM(R.string.brightness_medium, 50),
    HIGH(R.string.brightness_high, 100),
}

data class AppSettings(
    val timer: WakeTimer = WakeTimer.NONE,
    /** The length of the "Custom" timer, 1 to 1440 minutes. */
    val customTimerMinutes: Int = 45,
    /** Quick Settings tile: each tap moves to the next of [tileSteps]; after the last, it turns off. */
    val tileCycle: Boolean = false,
    /** The lengths the tile cycles through, in minutes (0: no limit), shortest first and "no limit" last. */
    val tileSteps: List<Int> = DEFAULT_TILE_STEPS,
    /** Let the screen dim when idle instead of holding it at full brightness. */
    val allowDim: Boolean = false,
    val brightness: Brightness = Brightness.UNCHANGED,
    val lowBattery: LowBattery = LowBattery.OFF,
    /** Stop when the battery gets hot. */
    val heatStop: HeatStop = HeatStop.OFF,
    /** Pocket guard: let the screen sleep while the proximity sensor is covered. */
    val pocketGuard: Boolean = false,
    /** Remind after 3 hours on battery. */
    val longReminder: Boolean = true,
    /** Turn the manual keep-awake off when the screen goes off (power button or the phone's own timeout). */
    val offWhenScreenOff: Boolean = false,
    /** Turn a manual "keep awake" back on after the phone restarts. */
    val resumeAfterRestart: Boolean = false,
    val appWake: Boolean = false,
    val appWakePackages: Set<String> = emptySet(),
    /** AppWake apps that keep the screen on only while the phone is also charging. */
    val appWakeChargingOnly: Set<String> = emptySet(),
    /** AppWake apps with a time limit: package → minutes the screen is kept on each time the app comes on screen. */
    val appWakeTimers: Map<String, Int> = emptyMap(),
    val chargeWake: Boolean = false,
    val btWake: Boolean = false,
    val btWakeDevices: Set<String> = emptySet(),
    /** MediaWake: while music, a podcast or a video is playing on the phone. */
    val mediaWake: Boolean = false,
    /** DockWake: while Android reports the phone as docked (desk or car dock). */
    val dockWake: Boolean = false,
    /** HeadsetWake: while headphones or a headset are connected (wired, USB or Bluetooth). */
    val headsetWake: Boolean = false,
    /** Schedule: keep the screen on during set hours. */
    val scheduleWake: Boolean = false,
    val scheduleWindows: List<com.vknxworks.wakectrl.wake.ScheduleWindow> = emptyList(),
    /** Let other apps (Tasker, Home Assistant…) turn WakeCtrl on and off with an intent. */
    val allowAutomation: Boolean = false,
    /** App lock: when it asks again after WakeCtrl was in the background. */
    val lockTimeout: LockTimeout = LockTimeout.IMMEDIATELY,
    /** "Hide in screenshots": FLAG_SECURE on the app's windows (screenshots, recordings and the Recents preview). */
    val hideScreenshots: Boolean = false,
    /** "Hide in recent apps": the Recents view shows a blank card. Screenshots still work. */
    val hideRecents: Boolean = false,
) {
    /** The manual timer's length in minutes, 0 for no limit. */
    val timerMinutes: Int
        get() = if (timer == WakeTimer.CUSTOM) customTimerMinutes else timer.minutes

    /** Any automation that needs WakeCtrl to watch in the background. */
    val anyAutomation: Boolean
        get() = (appWake && appWakePackages.isNotEmpty()) || chargeWake || (btWake && btWakeDevices.isNotEmpty()) ||
            (scheduleWake && scheduleWindows.any { it.days.isNotEmpty() }) || mediaWake || dockWake || headsetWake

    companion object {
        val DEFAULT_TILE_STEPS = listOf(15, 30, 60, 0)
    }
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val timer = stringPreferencesKey("timer")
        val customTimer = intPreferencesKey("custom_timer_minutes")
        val tileCycle = booleanPreferencesKey("tile_cycle")
        val tileSteps = stringPreferencesKey("tile_steps")
        val heatStop = stringPreferencesKey("heat_stop")
        val pocketGuard = booleanPreferencesKey("pocket_guard")
        val appWakeTimers = stringSetPreferencesKey("app_wake_timers")
        val mediaWake = booleanPreferencesKey("media_wake")
        val dockWake = booleanPreferencesKey("dock_wake")
        val headsetWake = booleanPreferencesKey("headset_wake")
        val allowDim = booleanPreferencesKey("allow_dim")
        val brightness = stringPreferencesKey("brightness")
        val lowBattery = stringPreferencesKey("low_battery")
        val longReminder = booleanPreferencesKey("long_reminder")
        val offWhenScreenOff = booleanPreferencesKey("off_when_screen_off")
        val resume = booleanPreferencesKey("resume_after_restart")
        val appWake = booleanPreferencesKey("app_wake")
        val appWakePackages = stringSetPreferencesKey("app_wake_packages")
        val appWakeChargingOnly = stringSetPreferencesKey("app_wake_charging_only")
        val chargeWake = booleanPreferencesKey("charge_wake")
        val btWake = booleanPreferencesKey("bt_wake")
        val btWakeDevices = stringSetPreferencesKey("bt_wake_devices")
        /** The removed DevWake switch; dropped from saved settings on the next save. */
        val removedDevWake = booleanPreferencesKey("dev_wake")
        val scheduleWake = booleanPreferencesKey("schedule_wake")
        val scheduleWindows = stringPreferencesKey("schedule_windows")
        val allowAutomation = booleanPreferencesKey("allow_automation")
        val lockTimeout = stringPreferencesKey("lock_timeout")
        val hideScreenshots = booleanPreferencesKey("hide_screenshots")
        val hideRecents = booleanPreferencesKey("hide_recents")
        val manualOn = booleanPreferencesKey("manual_on")
        /** When the manual keep-awake ends (epoch ms, 0: no limit) and how long it was started for (minutes). */
        val manualUntil = longPreferencesKey("manual_until")
        val manualMinutes = intPreferencesKey("manual_minutes")
    }

    private val prefs: Flow<Preferences> = context.appDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: StateFlow<AppSettings> = prefs
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /**
     * True once [settings] holds the saved settings instead of the placeholder defaults. Until then the window stays
     * hidden from screenshots and Recents, so "Hide in…" can't lapse for a moment at start-up.
     */
    val loaded: StateFlow<Boolean> = prefs
        .map { true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** The stored settings, read from storage (for receivers that run before [settings] has loaded). */
    suspend fun settingsLoaded(): AppSettings = prefs.first().toSettings()

    /** "Allow other apps", as stored (no default value emitted before the settings have loaded). */
    val allowAutomation: Flow<Boolean> = prefs.map { it[K.allowAutomation] ?: false }.distinctUntilChanged()

    /** Whether a manual "keep awake" was running when the phone went down (to resume it after a restart). */
    suspend fun wasManualOnNow(): Boolean = prefs.first()[K.manualOn] ?: false

    private fun Preferences.toSettings() = AppSettings(
        timer = enumOr(this[K.timer], WakeTimer.NONE),
        customTimerMinutes = (this[K.customTimer] ?: 45).coerceIn(1, TimerRule.MAX_MINUTES),
        tileCycle = this[K.tileCycle] ?: false,
        tileSteps = TimerRule.decodeSteps(this[K.tileSteps])?.let { TimerRule.sortSteps(it) }?.ifEmpty { null } ?: AppSettings.DEFAULT_TILE_STEPS,
        heatStop = enumOr(this[K.heatStop], HeatStop.OFF),
        pocketGuard = this[K.pocketGuard] ?: false,
        appWakeTimers = AppTimerRule.decode(this[K.appWakeTimers]),
        mediaWake = this[K.mediaWake] ?: false,
        dockWake = this[K.dockWake] ?: false,
        headsetWake = this[K.headsetWake] ?: false,
        allowDim = this[K.allowDim] ?: false,
        brightness = enumOr(this[K.brightness], Brightness.UNCHANGED),
        lowBattery = enumOr(this[K.lowBattery], LowBattery.OFF),
        longReminder = this[K.longReminder] ?: true,
        offWhenScreenOff = this[K.offWhenScreenOff] ?: false,
        resumeAfterRestart = this[K.resume] ?: false,
        appWake = this[K.appWake] ?: false,
        appWakePackages = this[K.appWakePackages] ?: emptySet(),
        appWakeChargingOnly = this[K.appWakeChargingOnly] ?: emptySet(),
        chargeWake = this[K.chargeWake] ?: false,
        btWake = this[K.btWake] ?: false,
        btWakeDevices = this[K.btWakeDevices] ?: emptySet(),
        scheduleWake = this[K.scheduleWake] ?: false,
        scheduleWindows = com.vknxworks.wakectrl.wake.ScheduleMath.decode(this[K.scheduleWindows]),
        allowAutomation = this[K.allowAutomation] ?: false,
        lockTimeout = enumOr(this[K.lockTimeout], LockTimeout.IMMEDIATELY),
        hideScreenshots = this[K.hideScreenshots] ?: false,
        hideRecents = this[K.hideRecents] ?: false,
    )

    /** Saves a change. If the phone's storage fails, the change is dropped instead of closing the app. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            context.appDataStore.edit { p ->
                val s = transform(p.toSettings())
                p[K.timer] = s.timer.name
                p[K.customTimer] = s.customTimerMinutes.coerceIn(1, TimerRule.MAX_MINUTES)
                p[K.tileCycle] = s.tileCycle
                p[K.tileSteps] = TimerRule.encodeSteps(s.tileSteps.ifEmpty { AppSettings.DEFAULT_TILE_STEPS })
                p[K.heatStop] = s.heatStop.name
                p[K.pocketGuard] = s.pocketGuard
                // Only apps that are still chosen can have a time limit.
                p[K.appWakeTimers] = AppTimerRule.encode(s.appWakeTimers.filterKeys { it in s.appWakePackages })
                p[K.mediaWake] = s.mediaWake
                p[K.dockWake] = s.dockWake
                p[K.headsetWake] = s.headsetWake
                p[K.allowDim] = s.allowDim
                p[K.brightness] = s.brightness.name
                p[K.lowBattery] = s.lowBattery.name
                p[K.longReminder] = s.longReminder
                p[K.offWhenScreenOff] = s.offWhenScreenOff
                p[K.resume] = s.resumeAfterRestart
                p[K.appWake] = s.appWake
                p[K.appWakePackages] = s.appWakePackages
                // Only apps that are still chosen can be "only while charging".
                p[K.appWakeChargingOnly] = s.appWakeChargingOnly intersect s.appWakePackages
                p[K.chargeWake] = s.chargeWake
                p[K.btWake] = s.btWake
                p[K.btWakeDevices] = s.btWakeDevices
                p.remove(K.removedDevWake)
                p[K.scheduleWake] = s.scheduleWake
                p[K.scheduleWindows] = com.vknxworks.wakectrl.wake.ScheduleMath.encode(s.scheduleWindows)
                p[K.allowAutomation] = s.allowAutomation
                p[K.lockTimeout] = s.lockTimeout.name
                p[K.hideScreenshots] = s.hideScreenshots
                p[K.hideRecents] = s.hideRecents
            }
        } catch (e: IOException) {
            // Nothing to do: the settings stay as they were.
        }
    }

    /**
     * Saves the manual keep-awake: whether it runs, when it ends ([until], epoch ms, 0: no limit) and the length it
     * was started with ([minutes]), so it can carry on if Android restarts the service.
     */
    suspend fun setManualOn(on: Boolean, until: Long = 0L, minutes: Int = 0) {
        try {
            context.appDataStore.edit {
                it[K.manualOn] = on
                if (on) {
                    it[K.manualUntil] = until
                    it[K.manualMinutes] = minutes
                } else {
                    it.remove(K.manualUntil)
                    it.remove(K.manualMinutes)
                }
            }
        } catch (_: IOException) {
        }
    }

    /**
     * The saved manual keep-awake, as (on, until, minutes); see [setManualOn]. minutes is null when only "on" was
     * saved (by a version before 0.8.2), so it isn't known how long it was meant to run.
     */
    suspend fun savedManual(): Triple<Boolean, Long, Int?> {
        val p = prefs.first()
        return Triple(p[K.manualOn] ?: false, p[K.manualUntil] ?: 0L, p[K.manualMinutes])
    }

    suspend fun clear() {
        try {
            context.appDataStore.edit { it.clear() }
        } catch (_: IOException) {
        }
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
