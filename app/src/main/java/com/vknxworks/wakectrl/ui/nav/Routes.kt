package com.vknxworks.wakectrl.ui.nav

import kotlinx.serialization.Serializable

// Tabs
@Serializable data object WakeRoute
@Serializable data object AutomationsRoute

// Pages opened from a tab
@Serializable data object AppPickerRoute
@Serializable data object DevicePickerRoute
/** A Schedule time window to change, or -1 for a new one. */
@Serializable data class ScheduleWindowRoute(val id: Int)

// Opened from the profile picture: the You page, and Settings (with the activity log) from there
@Serializable data object YouRoute
@Serializable data object SettingsRoute
@Serializable data object LogRoute
