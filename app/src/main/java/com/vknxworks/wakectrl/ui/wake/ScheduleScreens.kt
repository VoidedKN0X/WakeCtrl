package com.vknxworks.wakectrl.ui.wake

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AlarmOn
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vknxworks.wakectrl.R
import com.vknxworks.wakectrl.container
import com.vknxworks.wakectrl.data.settings.AppSettings
import com.vknxworks.wakectrl.ui.common.openSystemPage
import com.vknxworks.wakectrl.ui.components.ConfirmDialog
import com.vknxworks.wakectrl.ui.components.CtrlAlertDialog
import com.vknxworks.wakectrl.ui.components.CtrlSquareButton
import com.vknxworks.wakectrl.ui.components.FieldNote
import com.vknxworks.wakectrl.ui.components.Group
import com.vknxworks.wakectrl.ui.components.GroupRow
import com.vknxworks.wakectrl.ui.components.GroupSwitch
import com.vknxworks.wakectrl.ui.components.PickerField
import com.vknxworks.wakectrl.ui.components.ScreenTopBar
import com.vknxworks.wakectrl.ui.components.SectionLabel
import com.vknxworks.wakectrl.ui.nav.LocalNavigator
import com.vknxworks.wakectrl.ui.nav.ScheduleWindowRoute
import com.vknxworks.wakectrl.ui.theme.CtrlColors
import com.vknxworks.wakectrl.ui.theme.CtrlShapes
import com.vknxworks.wakectrl.ui.theme.CtrlType
import com.vknxworks.wakectrl.ui.theme.Gutter
import com.vknxworks.wakectrl.wake.ScheduleAlarm
import com.vknxworks.wakectrl.wake.ScheduleMath
import com.vknxworks.wakectrl.wake.ScheduleWindow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** "08:00" or "8:00 AM", following the phone's 12/24-hour setting. */
fun formatMinutes(context: Context, minutes: Int): String {
    val t = LocalTime.of((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59))
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, t.hour)
        set(java.util.Calendar.MINUTE, t.minute)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(cal.time)
}

/** The days of the week in the order the phone's locale starts the week. */
private fun weekDays(): List<DayOfWeek> {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return (0 until 7).map { first.plus(it.toLong()) }
}

@Composable
private fun daysSummary(days: Set<Int>): String = when {
    days.isEmpty() -> stringResource(R.string.schedule_days_none)
    days.size == 7 -> stringResource(R.string.schedule_days_every)
    days == (1..5).toSet() -> stringResource(R.string.schedule_days_weekdays)
    days == setOf(6, 7) -> stringResource(R.string.schedule_days_weekend)
    else -> weekDays().filter { it.value in days }.joinToString { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

@Composable
private fun windowSubtitle(w: ScheduleWindow): String {
    val days = daysSummary(w.days)
    return when {
        w.start == w.end -> stringResource(R.string.schedule_whole_day, days)
        w.crossesMidnight -> stringResource(R.string.schedule_next_day, days)
        else -> days
    }
}

/** ScheduleWake on the Automations tab: the switch, the time windows and adding one. */
@Composable
fun ScheduleGroup(settings: AppSettings, tick: Int, set: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    Group(stringResource(R.string.schedule_wake), footer = stringResource(R.string.schedule_wake_footer)) {
        GroupSwitch(
            stringResource(R.string.schedule_wake_switch),
            stringResource(R.string.schedule_wake_sub),
            settings.scheduleWake,
            Icons.Outlined.Schedule,
        ) { on ->
            set { s ->
                // Switching it on with nothing set yet: start from weekdays 08:00–17:30.
                if (on && s.scheduleWindows.isEmpty()) s.copy(scheduleWake = true, scheduleWindows = listOf(ScheduleWindow(id = 1)))
                else s.copy(scheduleWake = on)
            }
        }
        settings.scheduleWindows.forEach { w ->
            GroupRow(
                stringResource(R.string.schedule_window, formatMinutes(context, w.start), formatMinutes(context, w.end)),
                windowSubtitle(w),
                showChevron = true,
                onClick = { nav.navigate(ScheduleWindowRoute(w.id)) },
            )
        }
        if (settings.scheduleWindows.size < ScheduleMath.MAX_WINDOWS) {
            GroupRow(
                stringResource(R.string.schedule_add),
                if (settings.scheduleWindows.isEmpty()) stringResource(R.string.schedule_none) else null,
                icon = Icons.Outlined.Add,
                onClick = { nav.navigate(ScheduleWindowRoute(-1)) },
            )
        }
        val exactOk = remember(tick) { ScheduleAlarm.canExact(context) }
        if (settings.scheduleWake && !exactOk) {
            GroupRow(
                stringResource(R.string.schedule_exact),
                stringResource(R.string.schedule_exact_sub),
                icon = Icons.Outlined.AlarmOn,
                iconTint = CtrlColors.Warning,
                showChevron = true,
                onClick = { openSystemPage(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) },
            )
        }
    }
}

/** Adding (id -1) or changing a time window. */
@Composable
fun ScheduleWindowScreen(id: Int) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val original = remember(id) { c.settings.settings.value.scheduleWindows.firstOrNull { it.id == id } }
    val start = remember(id) { original ?: ScheduleWindow(id = -1) }
    var from by remember { mutableIntStateOf(start.start) }
    var to by remember { mutableIntStateOf(start.end) }
    var days by remember { mutableStateOf(start.days) }
    var picking by remember { mutableStateOf<Int?>(null) } // 0: start, 1: end
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        scope.launch {
            c.settings.update { s ->
                val list = s.scheduleWindows
                val w = if (original == null) {
                    ScheduleWindow(id = (list.maxOfOrNull { it.id } ?: 0) + 1, days = days, start = from, end = to)
                } else {
                    original.copy(days = days, start = from, end = to)
                }
                val next = if (original == null) list + w else list.map { if (it.id == w.id) w else it }
                // Adding a window switches the schedule on.
                s.copy(scheduleWindows = next.take(ScheduleMath.MAX_WINDOWS), scheduleWake = s.scheduleWake || original == null)
            }
            c.refreshWake()
        }
        nav.back()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            stringResource(if (original == null) R.string.schedule_new_title else R.string.schedule_edit_title),
            actions = {
                if (original != null) CtrlSquareButton(Icons.Outlined.Delete, stringResource(R.string.schedule_delete), onClick = { confirmDelete = true }, tint = CtrlColors.Error)
                CtrlSquareButton(Icons.Outlined.Check, stringResource(R.string.save), onClick = ::save, tint = CtrlColors.AccentText, enabled = days.isNotEmpty())
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = Gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerField(stringResource(R.string.schedule_start), formatMinutes(context, from), onClick = { picking = 0 }, modifier = Modifier.weight(1f), icon = Icons.Outlined.Schedule)
                PickerField(stringResource(R.string.schedule_end), formatMinutes(context, to), onClick = { picking = 1 }, modifier = Modifier.weight(1f), icon = Icons.Outlined.Schedule)
            }
            if (to <= from) {
                Text(
                    if (to == from) stringResource(R.string.schedule_whole_day, daysSummary(days)) else stringResource(R.string.schedule_next_day, daysSummary(days)),
                    style = CtrlType.Data,
                    color = CtrlColors.Muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            SectionLabel(stringResource(R.string.schedule_days), Modifier.padding(start = 4.dp, top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                weekDays().forEach { d ->
                    val on = d.value in days
                    val full = d.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    Box(
                        Modifier.weight(1f).size(44.dp).clip(CtrlShapes.Control)
                            .background(if (on) CtrlColors.Accent else CtrlColors.Surface)
                            .border(1.dp, if (on) CtrlColors.Accent else CtrlColors.ControlOutline, CtrlShapes.Control)
                            .clickable(role = Role.Checkbox) { days = if (on) days - d.value else days + d.value }
                            .semantics { contentDescription = full; selected = on },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = CtrlType.LabelStrong, color = if (on) CtrlColors.OnAccent else CtrlColors.Text)
                    }
                }
            }
            FieldNote(stringResource(R.string.schedule_edit_note))
            Spacer(Modifier.height(24.dp))
        }
    }

    picking?.let { which ->
        val initial = if (which == 0) from else to
        val state = rememberTimePickerState(initial / 60, initial % 60, android.text.format.DateFormat.is24HourFormat(context))
        CtrlAlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(if (which == 0) R.string.schedule_start else R.string.schedule_end)) },
            text = {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = CtrlColors.Background,
                        selectorColor = CtrlColors.Accent,
                        containerColor = CtrlColors.Surface,
                        clockDialSelectedContentColor = CtrlColors.OnAccent,
                        clockDialUnselectedContentColor = CtrlColors.Text,
                        periodSelectorSelectedContainerColor = CtrlColors.Accent,
                        periodSelectorSelectedContentColor = CtrlColors.OnAccent,
                        periodSelectorUnselectedContentColor = CtrlColors.Text,
                        periodSelectorBorderColor = CtrlColors.ControlOutline,
                        timeSelectorSelectedContainerColor = CtrlColors.Accent,
                        timeSelectorSelectedContentColor = CtrlColors.OnAccent,
                        timeSelectorUnselectedContainerColor = CtrlColors.Background,
                        timeSelectorUnselectedContentColor = CtrlColors.Text,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    if (which == 0) from = m else to = m
                    picking = null
                }) { Text(stringResource(R.string.schedule_ok), color = CtrlColors.AccentText) }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel), color = CtrlColors.Muted) }
            },
        )
    }

    if (confirmDelete && original != null) {
        ConfirmDialog(
            title = stringResource(R.string.schedule_delete),
            body = stringResource(R.string.schedule_delete_body),
            confirm = stringResource(R.string.schedule_delete_confirm),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    c.settings.update { s -> s.copy(scheduleWindows = s.scheduleWindows.filter { it.id != original.id }) }
                    c.refreshWake()
                }
                nav.back()
            },
        )
    }
}
