package com.vknxworks.wakectrl.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.wakectrl.R
import com.vknxworks.wakectrl.container
import com.vknxworks.wakectrl.data.lock.LockVault
import com.vknxworks.wakectrl.data.settings.AppSettings
import com.vknxworks.wakectrl.data.settings.LockTimeout
import com.vknxworks.wakectrl.data.settings.Brightness
import com.vknxworks.wakectrl.data.settings.LowBattery
import com.vknxworks.wakectrl.ui.common.openSystemPage
import com.vknxworks.wakectrl.ui.nav.LocalNavigator
import com.vknxworks.wakectrl.ui.nav.LogRoute
import com.vknxworks.wakectrl.ui.theme.CtrlColors
import com.vknxworks.wakectrl.ui.wake.requestUnrestrictedBattery
import android.os.PowerManager
import android.provider.Settings
import com.vknxworks.wakectrl.ui.components.Group
import com.vknxworks.wakectrl.ui.components.GroupChoice
import com.vknxworks.wakectrl.ui.components.GroupRow
import com.vknxworks.wakectrl.ui.components.GroupSwitch
import com.vknxworks.wakectrl.ui.components.ScreenTopBar
import com.vknxworks.wakectrl.ui.lock.AppLock
import com.vknxworks.wakectrl.ui.lock.PinRequest
import com.vknxworks.wakectrl.ui.lock.findActivity
import kotlinx.coroutines.launch

/** Settings, opened from the You page (tap the profile picture). WakeCtrl follows the phone's dark theme. */
@Composable
private fun rememberResumeTickSettings(): Int {
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    fun set(transform: (AppSettings) -> AppSettings) {
        scope.launch {
            c.settings.update(transform)
            c.refreshWake()
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.settings))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            val tick = rememberResumeTickSettings()
            Group(stringResource(R.string.group_screen), footer = stringResource(R.string.screen_footer)) {
                GroupChoice(
                    stringResource(R.string.brightness),
                    Brightness.entries.map { it to stringResource(it.label) },
                    settings.brightness,
                    icon = Icons.Outlined.BrightnessMedium,
                ) { b -> set { it.copy(brightness = b) } }
                if (settings.brightness != Brightness.UNCHANGED && !remember(tick) { Settings.System.canWrite(context) }) {
                    GroupRow(
                        stringResource(R.string.allow_brightness),
                        stringResource(R.string.allow_brightness_sub),
                        icon = Icons.Outlined.Tune,
                        iconTint = CtrlColors.Warning,
                        showChevron = true,
                        onClick = { openSystemPage(context, Settings.ACTION_MANAGE_WRITE_SETTINGS) },
                    )
                }
                GroupSwitch(stringResource(R.string.allow_dim), stringResource(R.string.allow_dim_sub), settings.allowDim, Icons.Outlined.Contrast) { on -> set { it.copy(allowDim = on) } }
            }
            Group(stringResource(R.string.group_safety)) {
                GroupChoice(
                    stringResource(R.string.low_battery),
                    LowBattery.entries.map { it to stringResource(it.label) },
                    settings.lowBattery,
                    icon = Icons.Outlined.BatteryAlert,
                ) { l -> set { it.copy(lowBattery = l) } }
                GroupSwitch(stringResource(R.string.off_when_screen_off), stringResource(R.string.off_when_screen_off_sub), settings.offWhenScreenOff, Icons.Outlined.ScreenLockPortrait) { on -> set { it.copy(offWhenScreenOff = on) } }
                GroupSwitch(stringResource(R.string.long_reminder), stringResource(R.string.long_reminder_sub), settings.longReminder, Icons.Outlined.NotificationsActive) { on -> set { it.copy(longReminder = on) } }
                GroupSwitch(stringResource(R.string.resume_restart), stringResource(R.string.resume_restart_sub), settings.resumeAfterRestart, Icons.Outlined.RestartAlt) { on -> set { it.copy(resumeAfterRestart = on) } }
            }
            Group(stringResource(R.string.group_reliability), footer = stringResource(R.string.reliability_footer)) {
                val batteryOk = remember(tick) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
                GroupRow(
                    stringResource(R.string.reliability_battery),
                    stringResource(if (batteryOk) R.string.status_allowed else R.string.reliability_battery_sub),
                    icon = Icons.Outlined.BatteryChargingFull,
                    iconTint = if (batteryOk) CtrlColors.Text else CtrlColors.Warning,
                    showChevron = true,
                    onClick = { requestUnrestrictedBattery(context) },
                )
            }
            Group(stringResource(R.string.group_automation), footer = stringResource(R.string.automation_footer)) {
                GroupSwitch(stringResource(R.string.allow_automation), stringResource(R.string.allow_automation_sub), settings.allowAutomation, Icons.Outlined.Hub) { on -> set { it.copy(allowAutomation = on) } }
            }
            Group {
                GroupRow(
                    stringResource(R.string.activity_log),
                    stringResource(R.string.activity_log_sub),
                    icon = Icons.Outlined.ReceiptLong,
                    showChevron = true,
                    onClick = { nav.navigate(LogRoute) },
                )
            }
            PrivacyAndSecurityGroup(settings)
        }
    }
}

/** Privacy and security: the shared Ctrl series app lock, then hiding WakeCtrl in screenshots. */
@Composable
private fun PrivacyAndSecurityGroup(settings: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val bioAvailable = remember { AppLock.biometricAvailable(context) }
    val lockOn = c.vault.enabled
    Group(stringResource(R.string.group_privacy), footer = stringResource(R.string.app_lock_footer)) {
        GroupSwitch(
            stringResource(R.string.app_lock),
            stringResource(R.string.app_lock_subtitle),
            checked = lockOn,
            icon = Icons.Outlined.Lock,
        ) { on -> AppLock.request = if (on) PinRequest.ENABLE else PinRequest.DISABLE }
        if (lockOn) {
            GroupRow(
                stringResource(R.string.lock_change_pin),
                stringResource(R.string.lock_change_pin_subtitle, LockVault.PIN_MIN),
                icon = Icons.Outlined.Password,
                showChevron = true,
                onClick = { AppLock.request = PinRequest.CHANGE },
            )
            GroupSwitch(
                stringResource(R.string.lock_fingerprint),
                stringResource(if (bioAvailable || c.vault.biometricEnabled) R.string.lock_fingerprint_subtitle else R.string.lock_fingerprint_unavailable),
                checked = c.vault.biometricEnabled,
                icon = Icons.Outlined.Fingerprint,
            ) { on ->
                if (!on) {
                    c.vault.disableBiometric()
                } else if (bioAvailable) {
                    val activity = context.findActivity()
                    val cipher = c.vault.biometricEnrolCipher()
                    if (activity == null || cipher == null) {
                        Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        AppLock.biometricPrompt(
                            activity,
                            context.getString(R.string.lock_fingerprint_enable_title),
                            context.getString(R.string.lock_fingerprint_enable_subtitle),
                            cipher,
                        ) { unlocked ->
                            if (!c.vault.finishBiometricEnrol(unlocked)) {
                                Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            GroupChoice(
                stringResource(R.string.lock_again),
                LockTimeout.entries.map { it to stringResource(it.label) },
                settings.lockTimeout,
                icon = Icons.Outlined.Timer,
            ) { t -> c.appScope.launch { c.settings.update { it.copy(lockTimeout = t) } } }
        }
        GroupSwitch(
            stringResource(R.string.hide_screenshots),
            stringResource(R.string.hide_screenshots_subtitle),
            checked = settings.hideScreenshots,
            icon = Icons.Outlined.VisibilityOff,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideScreenshots = on) } } }
        GroupSwitch(
            stringResource(R.string.hide_recents),
            stringResource(R.string.hide_recents_subtitle),
            checked = settings.hideRecents,
            icon = Icons.Outlined.Layers,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideRecents = on) } } }
    }
}
