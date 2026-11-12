package com.vknxworks.wakectrl.wake

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Why the screen is being kept on. */
enum class Reason { MANUAL, APP, CHARGING, BLUETOOTH, DEBUG_APP }

data class WakeStatus(
    /** The service is running (keeping awake or watching for an automation). */
    val running: Boolean = false,
    val reasons: Set<Reason> = emptySet(),
    /** Wall-clock time a manual keep-awake ends, or 0 for no limit. */
    val manualUntil: Long = 0L,
    /** How the screen is held: overlay (most reliable) or a wake lock. */
    val method: Method? = null,
    /** Stood down because the battery got low; automations wait until the phone charges. */
    val stoodDown: Boolean = false,
    /** The app on screen that triggered AppWake or DevWake, if any. */
    val triggerApp: String? = null,
) {
    val active: Boolean get() = reasons.isNotEmpty()
    val manual: Boolean get() = Reason.MANUAL in reasons
}

enum class Method { OVERLAY, WAKE_LOCK_BRIGHT, WAKE_LOCK_DIM }

/** Process-wide state shared by the service, the tile, the widget and the UI. */
object WakeState {
    private val _status = MutableStateFlow(WakeStatus())
    val status: StateFlow<WakeStatus> = _status.asStateFlow()

    internal fun set(transform: (WakeStatus) -> WakeStatus) = _status.update(transform)
}

/**
 * A small local log of what WakeCtrl did and why (the original app's "debug logs"). Kept in the app's
 * private storage, last 300 lines, never sent anywhere.
 */
object ActivityLog {
    private const val MAX = 300
    private val lines = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = lines.asStateFlow()
    private var file: File? = null
    private val fmt = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "activity.log")
        file = f
        lines.value = runCatching { f.readLines().takeLast(MAX) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(text: String) {
        val line = fmt.format(Date()) + "  " + text
        val next = (lines.value + line).takeLast(MAX)
        lines.value = next
        file?.let { f -> runCatching { f.writeText(next.joinToString("\n") + "\n") } }
    }

    @Synchronized
    fun clear() {
        lines.value = emptyList()
        file?.delete()
    }
}
