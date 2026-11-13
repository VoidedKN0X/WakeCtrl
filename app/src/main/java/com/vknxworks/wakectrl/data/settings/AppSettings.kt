package com.vknxworks.wakectrl.data.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.wakectrl.R
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
    /** Let the screen dim when idle instead of holding it at full brightness. */
    val allowDim: Boolean = false,
    val brightness: Brightness = Brightness.UNCHANGED,
    val lowBattery: LowBattery = LowBattery.OFF,
    /** Remind after 3 hours on battery. */
    val longReminder: Boolean = true,
    /** Turn a manual "keep awake" back on after the phone restarts. */
    val resumeAfterRestart: Boolean = false,
    val appWake: Boolean = false,
    val appWakePackages: Set<String> = emptySet(),
    val chargeWake: Boolean = false,
    val btWake: Boolean = false,
    val btWakeDevices: Set<String> = emptySet(),
    val devWake: Boolean = false,
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
    /** Any automation that needs WakeCtrl to watch in the background. */
    val anyAutomation: Boolean
        get() = (appWake && appWakePackages.isNotEmpty()) || chargeWake || (btWake && btWakeDevices.isNotEmpty()) || devWake ||
            (scheduleWake && scheduleWindows.any { it.days.isNotEmpty() })
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val timer = stringPreferencesKey("timer")
        val allowDim = booleanPreferencesKey("allow_dim")
        val brightness = stringPreferencesKey("brightness")
        val lowBattery = stringPreferencesKey("low_battery")
        val longReminder = booleanPreferencesKey("long_reminder")
        val resume = booleanPreferencesKey("resume_after_restart")
        val appWake = booleanPreferencesKey("app_wake")
        val appWakePackages = stringSetPreferencesKey("app_wake_packages")
        val chargeWake = booleanPreferencesKey("charge_wake")
        val btWake = booleanPreferencesKey("bt_wake")
        val btWakeDevices = stringSetPreferencesKey("bt_wake_devices")
        val devWake = booleanPreferencesKey("dev_wake")
        val scheduleWake = booleanPreferencesKey("schedule_wake")
        val scheduleWindows = stringPreferencesKey("schedule_windows")
        val allowAutomation = booleanPreferencesKey("allow_automation")
        val lockTimeout = stringPreferencesKey("lock_timeout")
        val hideScreenshots = booleanPreferencesKey("hide_screenshots")
        val hideRecents = booleanPreferencesKey("hide_recents")
        val manualOn = booleanPreferencesKey("manual_on")
    }

    private val prefs: Flow<Preferences> = context.appDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: StateFlow<AppSettings> = prefs
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** The stored settings, read from storage (for receivers that run before [settings] has loaded). */
    suspend fun settingsLoaded(): AppSettings = prefs.first().toSettings()

    /** "Allow other apps", as stored (no default value emitted before the settings have loaded). */
    val allowAutomation: Flow<Boolean> = prefs.map { it[K.allowAutomation] ?: false }.distinctUntilChanged()

    /** Whether a manual "keep awake" was running when the phone went down (to resume it after a restart). */
    suspend fun wasManualOnNow(): Boolean = prefs.first()[K.manualOn] ?: false

    private fun Preferences.toSettings() = AppSettings(
        timer = enumOr(this[K.timer], WakeTimer.NONE),
        allowDim = this[K.allowDim] ?: false,
        brightness = enumOr(this[K.brightness], Brightness.UNCHANGED),
        lowBattery = enumOr(this[K.lowBattery], LowBattery.OFF),
        longReminder = this[K.longReminder] ?: true,
        resumeAfterRestart = this[K.resume] ?: false,
        appWake = this[K.appWake] ?: false,
        appWakePackages = this[K.appWakePackages] ?: emptySet(),
        chargeWake = this[K.chargeWake] ?: false,
        btWake = this[K.btWake] ?: false,
        btWakeDevices = this[K.btWakeDevices] ?: emptySet(),
        devWake = this[K.devWake] ?: false,
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
                p[K.allowDim] = s.allowDim
                p[K.brightness] = s.brightness.name
                p[K.lowBattery] = s.lowBattery.name
                p[K.longReminder] = s.longReminder
                p[K.resume] = s.resumeAfterRestart
                p[K.appWake] = s.appWake
                p[K.appWakePackages] = s.appWakePackages
                p[K.chargeWake] = s.chargeWake
                p[K.btWake] = s.btWake
                p[K.btWakeDevices] = s.btWakeDevices
                p[K.devWake] = s.devWake
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

    suspend fun setManualOn(on: Boolean) {
        try {
            context.appDataStore.edit { it[K.manualOn] = on }
        } catch (_: IOException) {
        }
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
