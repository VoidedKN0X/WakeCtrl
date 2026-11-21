package com.vknxworks.wakectrl.ui.wake

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vknxworks.wakectrl.R
import com.vknxworks.wakectrl.container
import com.vknxworks.wakectrl.data.settings.AppSettings
import com.vknxworks.wakectrl.data.settings.WakeTimer
import com.vknxworks.wakectrl.ui.components.ChoiceSheet
import com.vknxworks.wakectrl.ui.components.CtrlAlertDialog
import com.vknxworks.wakectrl.ui.components.CtrlBottomSheet
import com.vknxworks.wakectrl.ui.components.CtrlTextField
import com.vknxworks.wakectrl.ui.components.Group
import com.vknxworks.wakectrl.ui.components.GroupChoice
import com.vknxworks.wakectrl.ui.components.GroupRow
import com.vknxworks.wakectrl.ui.components.GroupSwitch
import com.vknxworks.wakectrl.ui.theme.CtrlColors
import com.vknxworks.wakectrl.ui.theme.Gutter
import com.vknxworks.wakectrl.wake.TimerRule
import com.vknxworks.wakectrl.wake.minutesText
import kotlinx.coroutines.launch

/** Asks for a number of minutes (1 to 1440). */
@Composable
fun MinutesDialog(title: String, initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.takeIf { it > 0 }?.toString().orEmpty()) }
    val value = TimerRule.parseCustom(text)
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                CtrlTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.timer_custom_field)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (value != null) minutesText(LocalContext.current, value) else stringResource(R.string.timer_custom_range, TimerRule.MAX_MINUTES),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (value != null || text.isEmpty()) CtrlColors.Muted else CtrlColors.Error,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
    )
}

/** The Timer group on the Wake tab: the length (with your own), and what the Quick Settings tile does. */
@Composable
fun TimerGroup(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    var askCustom by remember { mutableStateOf(false) }
    var editSteps by remember { mutableStateOf(false) }
    Group(stringResource(R.string.group_timer), footer = stringResource(R.string.timer_footer)) {
        GroupChoice(
            stringResource(R.string.timer),
            WakeTimer.entries.map { t ->
                t to if (t == WakeTimer.CUSTOM) stringResource(R.string.timer_custom_value, minutesText(context, settings.customTimerMinutes)) else stringResource(t.label)
            },
            settings.timer,
            icon = Icons.Outlined.Timer,
        ) { t -> if (t == WakeTimer.CUSTOM) askCustom = true else set { it.copy(timer = t) } }
        if (settings.timer == WakeTimer.CUSTOM) {
            GroupRow(
                stringResource(R.string.timer_custom_edit),
                minutesText(context, settings.customTimerMinutes),
                icon = Icons.Outlined.Tune,
                showChevron = true,
                onClick = { askCustom = true },
            )
        }
    }
    Group(stringResource(R.string.group_tile), footer = stringResource(R.string.tile_footer)) {
        GroupSwitch(stringResource(R.string.tile_cycle), stringResource(R.string.tile_cycle_sub), settings.tileCycle, Icons.Outlined.Repeat) { on ->
            set { it.copy(tileCycle = on) }
        }
        if (settings.tileCycle) {
            GroupRow(
                stringResource(R.string.tile_steps),
                settings.tileSteps.joinToString(" → ") { minutesText(context, it) },
                icon = Icons.Outlined.Tune,
                showChevron = true,
                onClick = { editSteps = true },
            )
        }
    }
    if (askCustom) {
        MinutesDialog(stringResource(R.string.timer_custom_title), settings.customTimerMinutes, onDismiss = { askCustom = false }) { m ->
            askCustom = false
            set { it.copy(timer = WakeTimer.CUSTOM, customTimerMinutes = m) }
        }
    }
    if (editSteps) {
        TileStepsSheet(settings, onDismiss = { editSteps = false }) { steps -> set { it.copy(tileSteps = steps) } }
    }
}

/** Pick the lengths the tile cycles through. At least one stays on. */
@Composable
private fun TileStepsSheet(settings: AppSettings, onDismiss: () -> Unit, onChange: (List<Int>) -> Unit) {
    val context = LocalContext.current
    val options = TimerRule.sortSteps(
        WakeTimer.entries.filter { it.minutes > 0 }.map { it.minutes } + settings.customTimerMinutes + settings.tileSteps + 0,
    )
    CtrlBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.tile_steps), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Text(
            stringResource(R.string.tile_steps_note),
            style = MaterialTheme.typography.bodySmall,
            color = CtrlColors.Muted,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(vertical = 8.dp)) {
            options.forEach { m ->
                val on = m in settings.tileSteps
                GroupSwitch(minutesText(context, m), null, on) { want ->
                    val next = if (want) settings.tileSteps + m else settings.tileSteps - m
                    if (next.isNotEmpty()) onChange(TimerRule.sortSteps(next))
                }
            }
        }
    }
}

/** MediaWake, DockWake and HeadsetWake on the Automations tab. */
@Composable
fun MoreTriggerGroups(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    Group(stringResource(R.string.media_wake), footer = stringResource(R.string.media_wake_footer)) {
        GroupSwitch(stringResource(R.string.media_wake_switch), null, settings.mediaWake, Icons.Outlined.MusicNote) { on -> set { it.copy(mediaWake = on) } }
    }
    Group(stringResource(R.string.dock_wake), footer = stringResource(R.string.dock_wake_footer)) {
        GroupSwitch(stringResource(R.string.dock_wake_switch), null, settings.dockWake, Icons.Outlined.Weekend) { on -> set { it.copy(dockWake = on) } }
    }
    Group(stringResource(R.string.headset_wake), footer = stringResource(R.string.headset_wake_footer)) {
        GroupSwitch(stringResource(R.string.headset_wake_switch), null, settings.headsetWake, Icons.Outlined.Headphones) { on -> set { it.copy(headsetWake = on) } }
    }
}

/** AppWake: an app's own time limit, under its row in the app picker. */
@Composable
fun AppTimeLimitRow(pkg: String, limit: Int) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var choose by remember { mutableStateOf(false) }
    var askCustom by remember { mutableStateOf(false) }
    fun save(minutes: Int) {
        scope.launch {
            c.settings.update { s -> s.copy(appWakeTimers = if (minutes > 0) s.appWakeTimers + (pkg to minutes) else s.appWakeTimers - pkg) }
            c.refreshWake()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { choose = true }
            .padding(start = Gutter + 50.dp, end = Gutter, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Timer, null, Modifier.size(18.dp), tint = if (limit > 0) CtrlColors.AccentText else CtrlColors.Muted)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_time_limit), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text)
            Text(
                if (limit > 0) stringResource(R.string.app_time_limit_on, minutesText(context, limit)) else stringResource(R.string.app_time_limit_off),
                style = MaterialTheme.typography.bodySmall,
                color = CtrlColors.Muted,
            )
        }
    }
    if (choose) {
        val presets = WakeTimer.entries.filter { it.minutes > 0 }.map { it.minutes }
        val options = buildList {
            add(0 to stringResource(R.string.app_time_limit_none))
            presets.forEach { add(it to minutesText(context, it)) }
            if (limit > 0 && limit !in presets) add(limit to minutesText(context, limit))
            add(CUSTOM_CHOICE to stringResource(R.string.timer_custom_choice))
        }
        ChoiceSheet(stringResource(R.string.app_time_limit), options, limit, onDismiss = { choose = false }) { m ->
            choose = false
            if (m == CUSTOM_CHOICE) askCustom = true else save(m)
        }
    }
    if (askCustom) {
        MinutesDialog(stringResource(R.string.app_time_limit), limit, onDismiss = { askCustom = false }) { m ->
            askCustom = false
            save(m)
        }
    }
}

private const val CUSTOM_CHOICE = -1
