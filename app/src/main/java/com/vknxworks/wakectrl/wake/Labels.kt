package com.vknxworks.wakectrl.wake

import android.content.Context
import com.vknxworks.wakectrl.R
import java.util.Locale

/** "45 minutes", "2 hours", "1 hour 30 minutes", or "No limit" for 0. */
fun minutesText(context: Context, minutes: Int): String {
    val r = context.resources
    val h = minutes / 60
    val m = minutes % 60
    return when {
        minutes <= 0 -> context.getString(R.string.timer_none)
        h == 0 -> r.getQuantityString(R.plurals.timer_minutes, m, m)
        m == 0 -> r.getQuantityString(R.plurals.timer_hours, h, h)
        else -> context.getString(R.string.timer_hours_minutes, r.getQuantityString(R.plurals.timer_hours, h, h), r.getQuantityString(R.plurals.timer_minutes, m, m))
    }
}

/** A battery temperature in tenths of a degree, as "41.5 °C" (in the phone's number format). */
fun formatCelsius(tenths: Int): String = String.format(Locale.getDefault(), "%.1f °C", tenths / 10.0)
