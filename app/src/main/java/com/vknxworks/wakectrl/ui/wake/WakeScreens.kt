package com.vknxworks.wakectrl.ui.wake

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.wakectrl.R
import com.vknxworks.wakectrl.container
import com.vknxworks.wakectrl.data.settings.AppSettings
import com.vknxworks.wakectrl.data.settings.WakeTimer
import com.vknxworks.wakectrl.ui.common.formatDuration
import com.vknxworks.wakectrl.ui.common.hasPermission
import com.vknxworks.wakectrl.ui.common.launch
import com.vknxworks.wakectrl.ui.common.openSystemPage
import com.vknxworks.wakectrl.ui.components.CtrlButton
import com.vknxworks.wakectrl.ui.components.CtrlHeroCard
import com.vknxworks.wakectrl.ui.components.CtrlSwitch
import com.vknxworks.wakectrl.ui.components.CtrlTextField
import com.vknxworks.wakectrl.ui.components.Group
import com.vknxworks.wakectrl.ui.components.GroupChoice
import com.vknxworks.wakectrl.ui.components.GroupRow
import com.vknxworks.wakectrl.ui.components.GroupSwitch
import com.vknxworks.wakectrl.ui.components.MessageView
import com.vknxworks.wakectrl.ui.components.ScreenTopBar
import com.vknxworks.wakectrl.ui.components.TabTitle
import com.vknxworks.wakectrl.ui.components.WarningCard
import com.vknxworks.wakectrl.ui.components.bottomClearance
import com.vknxworks.wakectrl.ui.nav.AppPickerRoute
import com.vknxworks.wakectrl.ui.nav.DevicePickerRoute
import com.vknxworks.wakectrl.ui.nav.LocalNavigator
import com.vknxworks.wakectrl.ui.theme.CtrlColors
import com.vknxworks.wakectrl.ui.theme.CtrlType
import com.vknxworks.wakectrl.ui.theme.Gutter
import com.vknxworks.wakectrl.wake.Method
import com.vknxworks.wakectrl.wake.Reason
import com.vknxworks.wakectrl.wake.WakeService
import com.vknxworks.wakectrl.wake.WakeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Re-checks Android permissions each time the screen comes back (after a trip to Android's settings). */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

@Composable
fun reasonLabel(r: Reason): String = stringResource(
    when (r) {
        Reason.MANUAL -> R.string.reason_manual
        Reason.APP -> R.string.reason_app
        Reason.CHARGING -> R.string.reason_charging
        Reason.BLUETOOTH -> R.string.reason_bluetooth
        Reason.DEBUG_APP -> R.string.reason_debug
        Reason.SCHEDULE -> R.string.reason_schedule
    },
)

/** The Wake tab: the big switch, the timer and what WakeCtrl is doing right now. */
@Composable
fun WakeScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val status by WakeState.status.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.manualUntil) {
        while (status.manualUntil > 0) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun toggle() {
        if (!context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        WakeService.send(context, if (status.manual) WakeService.ACTION_OFF else WakeService.ACTION_ON, context.getString(R.string.log_source_app))
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        TabTitle(stringResource(R.string.tab_wake))
        CtrlHeroCard(
            label = stringResource(R.string.hero_label),
            meta = status.method?.let { stringResource(methodLabel(it)) },
            modifier = Modifier.padding(horizontal = Gutter),
            onClick = ::toggle,
        ) {
            Text(
                stringResource(if (status.active) R.string.state_on else R.string.state_off),
                style = CtrlType.Hero,
                color = CtrlColors.OnAccent,
            )
            Spacer(Modifier.height(6.dp))
            val detail = when {
                status.stoodDown -> stringResource(R.string.detail_stood_down)
                status.manual && status.manualUntil > 0 -> stringResource(R.string.detail_left, formatDuration(status.manualUntil - now))
                status.active -> stringResource(R.string.detail_because, status.reasons.map { reasonLabel(it) }.joinToString())
                settings.anyAutomation -> stringResource(R.string.detail_watching)
                else -> stringResource(R.string.detail_off)
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.OnAccent)
            status.triggerApp?.let { pkg ->
                Text(appLabel(context.packageManager, pkg), style = CtrlType.Data, color = CtrlColors.OnAccent)
            }
        }
        Spacer(Modifier.height(16.dp))
        CtrlButton(
            stringResource(if (status.manual) R.string.turn_off else R.string.turn_on),
            onClick = ::toggle,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
            icon = Icons.Outlined.LightMode,
        )
        Spacer(Modifier.height(20.dp))

        Group(stringResource(R.string.group_timer), footer = stringResource(R.string.timer_footer)) {
            GroupChoice(
                stringResource(R.string.timer),
                WakeTimer.entries.map { it to stringResource(it.label) },
                settings.timer,
                icon = Icons.Outlined.Timer,
            ) { t -> scope.launch { c.settings.update { it.copy(timer = t) } } }
        }

        // What helps WakeCtrl hold the screen reliably.
        key(tick) {
            val batteryOk = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            if (!batteryOk && (settings.anyAutomation || status.active)) {
                WarningCard(
                    stringResource(R.string.warn_battery),
                    Icons.Outlined.BatteryAlert,
                    Modifier.padding(horizontal = Gutter, vertical = 6.dp).clickable { requestUnrestrictedBattery(context) },
                )
            }
        }
        Spacer(Modifier.bottomClearance())
    }
}

@Composable
private fun key(tick: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(tick) { content() }

private fun methodLabel(m: Method) = when (m) {
    Method.WAKE_LOCK_BRIGHT -> R.string.method_wake_lock
    Method.WAKE_LOCK_DIM -> R.string.method_dim
}

@android.annotation.SuppressLint("BatteryLife")
fun requestUnrestrictedBattery(context: android.content.Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.fromParts("package", context.packageName, null))
    if (!context.launch(direct)) openSystemPage(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, withPackage = false)
}

fun appLabel(pm: PackageManager, pkg: String): String =
    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

/** The Automations tab: AppWake, ChargeWake, BTWake, ScheduleWake and DevWake. */
@Composable
fun AutomationsScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    fun set(transform: (AppSettings) -> AppSettings) {
        scope.launch {
            c.settings.update(transform)
            c.refreshWake()
        }
    }
    val bt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) nav.navigate(DevicePickerRoute)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        TabTitle(stringResource(R.string.tab_automations))
        val usageOk = remember(tick) { WakeService.hasUsageAccess(context) }

        Group(stringResource(R.string.app_wake), footer = stringResource(R.string.app_wake_footer)) {
            GroupSwitch(stringResource(R.string.app_wake_switch), stringResource(R.string.app_wake_sub), settings.appWake, Icons.Outlined.Apps) { on -> set { it.copy(appWake = on) } }
            GroupRow(
                stringResource(R.string.choose_apps),
                pluralStringResource(R.plurals.apps_selected, settings.appWakePackages.size, settings.appWakePackages.size),
                icon = Icons.Outlined.Search,
                showChevron = true,
                onClick = { nav.navigate(AppPickerRoute) },
            )
            if ((settings.appWake || settings.devWake) && !usageOk) UsageAccessRow()
        }
        Group(stringResource(R.string.charge_wake), footer = stringResource(R.string.charge_wake_footer)) {
            GroupSwitch(stringResource(R.string.charge_wake_switch), null, settings.chargeWake, Icons.Outlined.BatteryChargingFull) { on -> set { it.copy(chargeWake = on) } }
        }
        Group(stringResource(R.string.bt_wake), footer = stringResource(R.string.bt_wake_footer)) {
            GroupSwitch(stringResource(R.string.bt_wake_switch), null, settings.btWake, Icons.Outlined.Bluetooth) { on ->
                set { it.copy(btWake = on) }
                if (on && !context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) bt.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            GroupRow(
                stringResource(R.string.choose_devices),
                pluralStringResource(R.plurals.devices_selected, settings.btWakeDevices.size, settings.btWakeDevices.size),
                icon = Icons.Outlined.Search,
                showChevron = true,
                onClick = {
                    if (context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) nav.navigate(DevicePickerRoute)
                    else bt.launch(Manifest.permission.BLUETOOTH_CONNECT)
                },
            )
        }
        ScheduleGroup(settings, tick) { set(it) }
        Group(stringResource(R.string.dev_wake), footer = stringResource(R.string.dev_wake_footer)) {
            GroupSwitch(stringResource(R.string.dev_wake_switch), null, settings.devWake, Icons.Outlined.BugReport) { on -> set { it.copy(devWake = on) } }
            if (settings.devWake && !settings.appWake && !usageOk) UsageAccessRow()
        }
        val notificationsOk = remember(tick) { context.hasPermission(Manifest.permission.POST_NOTIFICATIONS) }
        if (!notificationsOk) {
            Group {
                GroupRow(
                    stringResource(R.string.allow_notifications),
                    stringResource(R.string.allow_notifications_sub),
                    icon = Icons.Outlined.Notifications,
                    showChevron = true,
                    onClick = { openSystemPage(context, Settings.ACTION_APP_NOTIFICATION_SETTINGS, withPackage = false) },
                )
            }
        }
        Spacer(Modifier.bottomClearance())
    }
}

@Composable
private fun UsageAccessRow() {
    val context = LocalContext.current
    GroupRow(
        stringResource(R.string.allow_usage),
        stringResource(R.string.allow_usage_sub),
        icon = Icons.Outlined.DataUsage,
        iconTint = CtrlColors.Warning,
        showChevron = true,
        onClick = { openSystemPage(context, Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackage = false) },
    )
}

private data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

/** Pick the apps that keep the screen on while they're open (AppWake). */
@Composable
fun AppPickerScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .map { pkg ->
                    AppEntry(pkg, appLabel(pm, pkg), runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull())
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.choose_apps))
        CtrlTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_apps)) },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Search, null) },
            singleLine = true,
        )
        val list = apps
        if (list == null) {
            MessageView(Icons.Outlined.Apps, stringResource(R.string.loading_apps))
        } else {
            val shown = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(shown, key = { it.pkg }) { a ->
                    val checked = a.pkg in settings.appWakePackages
                    fun flip() {
                        scope.launch {
                            c.settings.update { s -> s.copy(appWakePackages = if (checked) s.appWakePackages - a.pkg else s.appWakePackages + a.pkg) }
                            c.refreshWake()
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { flip() }.padding(horizontal = Gutter, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (a.icon != null) Image(a.icon, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, style = MaterialTheme.typography.bodyLarge, color = CtrlColors.Text)
                            Text(a.pkg, style = CtrlType.Data, color = CtrlColors.Muted)
                        }
                        CtrlSwitch(checked, { flip() })
                    }
                }
            }
        }
    }
}

/** Pick the Bluetooth devices that keep the screen on while connected (BTWake). */
@Composable
fun DevicePickerScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val devices = remember {
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.map { (it.name ?: it.address) to it.address }
                ?.sortedBy { it.first.lowercase() }
        }.getOrNull().orEmpty()
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.choose_devices))
        if (devices.isEmpty()) {
            MessageView(
                Icons.Outlined.Bluetooth,
                stringResource(R.string.no_devices),
                stringResource(R.string.no_devices_body),
                actionLabel = stringResource(R.string.open_bt_settings),
                onAction = { context.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            )
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 8.dp)) {
                Group {
                    devices.forEach { (name, address) ->
                        val checked = address in settings.btWakeDevices
                        GroupSwitch(name, address, checked, Icons.Outlined.Bluetooth) { on ->
                            scope.launch {
                                c.settings.update { s -> s.copy(btWakeDevices = if (on) s.btWakeDevices + address else s.btWakeDevices - address) }
                                c.refreshWake()
                            }
                        }
                    }
                }
            }
        }
    }
}
