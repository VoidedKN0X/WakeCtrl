package com.vknxworks.wakectrl.data.settings

import com.vknxworks.wakectrl.wake.AppTimerRule
import com.vknxworks.wakectrl.wake.ScheduleMath
import com.vknxworks.wakectrl.wake.ScheduleWindow
import com.vknxworks.wakectrl.wake.TimerRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A settings file: WakeCtrl's settings and automations, as JSON in a file the user picks. It holds no PIN and no
 * app-lock, privacy or "Allow other apps" settings, and importing one never changes those. Every field is
 * optional: a missing one leaves the current setting as it is.
 */
@Serializable
data class WakeBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val appVersion: String? = null,
    val createdAt: String? = null,
    val timer: String? = null,
    val customTimerMinutes: Int? = null,
    val tileCycle: Boolean? = null,
    val tileSteps: List<Int>? = null,
    val allowDim: Boolean? = null,
    val brightness: String? = null,
    val pocketGuard: Boolean? = null,
    val lowBattery: String? = null,
    val heatStop: String? = null,
    val longReminder: Boolean? = null,
    val offWhenScreenOff: Boolean? = null,
    val resumeAfterRestart: Boolean? = null,
    val appWake: Boolean? = null,
    val appWakePackages: List<String>? = null,
    val appWakeChargingOnly: List<String>? = null,
    val appWakeTimers: Map<String, Int>? = null,
    val chargeWake: Boolean? = null,
    val btWake: Boolean? = null,
    val btWakeDevices: List<String>? = null,
    val mediaWake: Boolean? = null,
    val dockWake: Boolean? = null,
    val headsetWake: Boolean? = null,
    val scheduleWake: Boolean? = null,
    val scheduleWindows: List<ScheduleWindow>? = null,
) {
    companion object {
        const val FORMAT = "wakectrl-settings"
        const val VERSION = 1
    }
}

/** Not a WakeCtrl settings file, or ([newer]) one from a newer WakeCtrl that this version can't read. */
class BackupFormatException(val newer: Boolean) : Exception()

object SettingsBackups {
    /** A settings file is a few kilobytes; anything far bigger isn't one. */
    const val MAX_BYTES = 512 * 1024
    private const val MAX_APPS = 500
    private const val MAX_DEVICES = 100
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val btAddress = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun export(s: AppSettings, appVersion: String, now: String): String = json.encodeToString(
        WakeBackup.serializer(),
        WakeBackup(
            appVersion = appVersion,
            createdAt = now,
            timer = s.timer.name,
            customTimerMinutes = s.customTimerMinutes,
            tileCycle = s.tileCycle,
            tileSteps = s.tileSteps,
            allowDim = s.allowDim,
            brightness = s.brightness.name,
            pocketGuard = s.pocketGuard,
            lowBattery = s.lowBattery.name,
            heatStop = s.heatStop.name,
            longReminder = s.longReminder,
            offWhenScreenOff = s.offWhenScreenOff,
            resumeAfterRestart = s.resumeAfterRestart,
            appWake = s.appWake,
            appWakePackages = s.appWakePackages.sorted(),
            appWakeChargingOnly = s.appWakeChargingOnly.intersect(s.appWakePackages).sorted(),
            appWakeTimers = s.appWakeTimers.filterKeys { it in s.appWakePackages }.toSortedMap(),
            chargeWake = s.chargeWake,
            btWake = s.btWake,
            btWakeDevices = s.btWakeDevices.sorted(),
            mediaWake = s.mediaWake,
            dockWake = s.dockWake,
            headsetWake = s.headsetWake,
            scheduleWake = s.scheduleWake,
            scheduleWindows = s.scheduleWindows,
        ),
    )

    fun parse(text: String): WakeBackup {
        val b = runCatching { json.decodeFromString(WakeBackup.serializer(), text) }.getOrNull()
            ?: throw BackupFormatException(newer = false)
        if (b.format != WakeBackup.FORMAT) throw BackupFormatException(newer = false)
        if (b.version > WakeBackup.VERSION) throw BackupFormatException(newer = true)
        return b
    }

    /**
     * The settings after importing [b] over [current]. Only what the file holds is replaced, and only valid values;
     * the app lock, "Hide in screenshots", "Hide in recent apps" and "Allow other apps" always stay as they are.
     */
    fun apply(b: WakeBackup, current: AppSettings): AppSettings {
        val packages = b.appWakePackages?.filter { it.length <= 255 && packageName.matches(it) }?.take(MAX_APPS)?.toSet()
        val chosen = packages ?: current.appWakePackages
        return current.copy(
            timer = enumOr(b.timer, current.timer),
            customTimerMinutes = b.customTimerMinutes?.takeIf { it in 1..TimerRule.MAX_MINUTES } ?: current.customTimerMinutes,
            tileCycle = b.tileCycle ?: current.tileCycle,
            tileSteps = b.tileSteps?.let { TimerRule.sortSteps(it).take(8) }?.ifEmpty { null } ?: current.tileSteps,
            allowDim = b.allowDim ?: current.allowDim,
            brightness = enumOr(b.brightness, current.brightness),
            pocketGuard = b.pocketGuard ?: current.pocketGuard,
            lowBattery = enumOr(b.lowBattery, current.lowBattery),
            heatStop = enumOr(b.heatStop, current.heatStop),
            longReminder = b.longReminder ?: current.longReminder,
            offWhenScreenOff = b.offWhenScreenOff ?: current.offWhenScreenOff,
            resumeAfterRestart = b.resumeAfterRestart ?: current.resumeAfterRestart,
            appWake = b.appWake ?: current.appWake,
            appWakePackages = chosen,
            appWakeChargingOnly = (b.appWakeChargingOnly?.toSet() ?: current.appWakeChargingOnly) intersect chosen,
            appWakeTimers = (b.appWakeTimers?.let { AppTimerRule.decode(AppTimerRule.encode(it)) } ?: current.appWakeTimers).filterKeys { it in chosen },
            chargeWake = b.chargeWake ?: current.chargeWake,
            btWake = b.btWake ?: current.btWake,
            btWakeDevices = b.btWakeDevices?.filter { btAddress.matches(it) }?.map { it.uppercase() }?.take(MAX_DEVICES)?.toSet() ?: current.btWakeDevices,
            mediaWake = b.mediaWake ?: current.mediaWake,
            dockWake = b.dockWake ?: current.dockWake,
            headsetWake = b.headsetWake ?: current.headsetWake,
            scheduleWake = b.scheduleWake ?: current.scheduleWake,
            // Through the same encode/decode as storage: cleaned and capped like a stored list.
            scheduleWindows = b.scheduleWindows?.let { ScheduleMath.decode(ScheduleMath.encode(it)) } ?: current.scheduleWindows,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
