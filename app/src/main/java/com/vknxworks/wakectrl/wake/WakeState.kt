package com.vknxworks.wakectrl.wake

import android.content.Context
import com.vknxworks.wakectrl.data.crypto.DataKeyStore
import com.vknxworks.wakectrl.data.crypto.KeystoreWrapper
import com.vknxworks.wakectrl.data.crypto.SealedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Why the screen is being kept on. */
enum class Reason { MANUAL, APP, CHARGING, BLUETOOTH, SCHEDULE, MEDIA, DOCK, HEADSET }

data class WakeStatus(
    /** The service is running (keeping awake or watching for an automation). */
    val running: Boolean = false,
    val reasons: Set<Reason> = emptySet(),
    /** Wall-clock time a manual keep-awake ends, or 0 for no limit. */
    val manualUntil: Long = 0L,
    /** How the screen is held: a bright screen wake lock, or a dim one for "Allow dimming". */
    val method: Method? = null,
    /** Stood down because the battery got low; automations wait until the phone charges. */
    val stoodDown: Boolean = false,
    /** The app on screen that triggered AppWake, if any. */
    val triggerApp: String? = null,
    /** While a Schedule window keeps the screen on: when it ends (epoch ms), or 0. */
    val scheduleUntil: Long = 0L,
    /** The length the manual keep-awake was started with, in minutes (0: no limit); only meaningful while it's on. */
    val manualMinutes: Int = 0,
    /** Pocket guard: the proximity sensor is covered, so the screen is allowed to sleep for now. */
    val pocketPaused: Boolean = false,
    /** Stopped because the battery got hot; automations wait until it cools down. */
    val heatStopped: Boolean = false,
    /** The battery temperature in tenths of a degree Celsius when it was last read, or null. */
    val batteryTenths: Int? = null,
) {
    val active: Boolean get() = reasons.isNotEmpty()
    val manual: Boolean get() = Reason.MANUAL in reasons
}

enum class Method { WAKE_LOCK_BRIGHT, WAKE_LOCK_DIM }

/** Process-wide state shared by the service, the tile and the UI. */
object WakeState {
    private val _status = MutableStateFlow(WakeStatus())
    val status: StateFlow<WakeStatus> = _status.asStateFlow()

    internal fun set(transform: (WakeStatus) -> WakeStatus) = _status.update(transform)
}

/**
 * A small local log of what WakeCtrl did and why (Settings > Activity log). Kept in the app's
 * private storage, last 300 lines, never sent anywhere.
 *
 * The file (`filesDir/activity.log`) is encrypted at rest ([SealedFile]) under the app's data key
 * ([DataKeyStore]). An ordinary file from an older version is still read and is sealed once, through
 * [SealedFile.sealInPlace]. Without a usable data key and with no sealed file, the log is read and written as
 * an ordinary file, as before. A sealed file that can't be opened is never overwritten or deleted on its own:
 * [unreadable] goes on, new lines stay in memory only, and the log screen offers "Try again" and "Start a new
 * log" (which renames the file aside).
 *
 * Everything that touches the file or the key runs on one background thread, in order.
 */
object ActivityLog {
    private const val MAX = 300
    private const val KEY_ALIAS = "wakectrl_data_at_rest"
    private val lines = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = lines.asStateFlow()

    /** The stored log has been read (or found unreadable); until then [entries] only holds lines from this run. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The stored log is sealed and can't be opened; nothing is written to it. */
    private val _unreadable = MutableStateFlow(false)
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    private var file: File? = null
    private var keys: DataKeyStore? = null
    private val fmt = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault())

    /** File and key work happens one task at a time on a background thread, never on the main thread. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "WakeCtrl-log").apply { isDaemon = true } }

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val dir = context.filesDir
        keys = DataKeyStore(dir, KeystoreWrapper(KEY_ALIAS))
        file = File(dir, FILE)
        writer.execute { runCatching { load() } }
    }

    @Synchronized
    fun add(text: String) {
        val line = fmt.format(Date()) + "  " + text
        lines.value = (lines.value + line).takeLast(MAX)
        if (file == null) return
        writer.execute { runCatching { persist() } }
    }

    /** The "Clear" button: empties the log. A stored log that can't be opened is left alone. */
    @Synchronized
    fun clear() {
        lines.value = emptyList()
        val f = file ?: return
        writer.execute {
            runCatching {
                if (!_unreadable.value) f.delete()
            }
        }
    }

    /**
     * "Forgot PIN?": the user chose to delete everything, so the log file goes even when it can't be opened,
     * together with any logs set aside earlier. A data key that can't be used any more goes too (nothing
     * sealed is left), so the next log gets a new one.
     */
    @Synchronized
    fun wipe() {
        lines.value = emptyList()
        val f = file ?: return
        val k = keys
        writer.execute {
            runCatching {
                f.delete()
                f.parentFile?.listFiles()?.forEach { if (it.name.startsWith(ASIDE_PREFIX)) it.delete() }
                File(f.parentFile, DataKeyStore.FILE + ".unreadable").delete()
                if (k != null && k.get(create = false) == null) k.discard(keep = false)
                _unreadable.value = false
                _ready.value = true
            }
        }
    }

    /** "Try again" on the log screen: asks the secure hardware for the key again and reads the stored log again. */
    fun retry() {
        writer.execute {
            runCatching {
                if (!_unreadable.value) return@runCatching
                keys?.reset()
                load()
                if (!_unreadable.value) persist()
            }
        }
    }

    /**
     * "Start a new log" on the log screen: the log that can't be opened is renamed aside (never deleted), a key
     * that can't be used is set aside as well, and the lines from this run are saved as the new log.
     */
    fun startNew() {
        writer.execute {
            runCatching {
                if (!_unreadable.value) return@runCatching
                val f = file ?: return@runCatching
                val k = keys ?: return@runCatching
                if (f.exists()) {
                    val aside = File(f.parentFile, ASIDE_PREFIX + System.currentTimeMillis())
                    if (!f.renameTo(aside)) return@runCatching
                }
                if (k.get(create = false) == null) k.discard(keep = true)
                _unreadable.value = false
                persist()
            }
        }
    }

    /** Reads the stored log and puts it in front of the lines added in this run. Runs on [writer]. */
    private fun load() {
        val f = file ?: return
        val k = keys ?: return
        var stored: List<String> = emptyList()
        var blocked = false
        var ordinary = false
        if (f.exists()) {
            val bytes = try {
                f.readBytes()
            } catch (e: Throwable) {
                null
            }
            if (bytes == null) {
                // It's there but can't be read right now: leave it alone rather than write over it.
                blocked = true
            } else {
                if (SealedFile.isSealed(bytes)) {
                    val key = k.get(create = false)
                    val plain = if (key == null) null else try {
                        SealedFile.open(key, bytes)
                    } catch (e: Throwable) {
                        null
                    }
                    if (plain == null) blocked = true else stored = decode(plain)
                } else {
                    stored = decode(bytes)
                    ordinary = true
                }
            }
        }
        synchronized(this) {
            if (!blocked) lines.value = (stored + lines.value).takeLast(MAX)
            _unreadable.value = blocked
            _ready.value = true
        }
        // An ordinary log from an older version: seal it where it is (checked copy, then one rename).
        if (ordinary) {
            val key = k.get(create = true)
            if (key != null) SealedFile.sealInPlace(key, f, File(f.parentFile, "$FILE.seal.tmp"))
        }
    }

    /** Saves the log as it is now: sealed when there is a data key, as an ordinary file otherwise. Runs on [writer]. */
    private fun persist() {
        if (!_ready.value || _unreadable.value) return
        val f = file ?: return
        val k = keys ?: return
        val plain = encode(lines.value)
        val key = k.get(create = true)
        if (key == null && SealedFile.isSealed(f)) {
            // Sealed by a key that is no longer there: don't overwrite it.
            _unreadable.value = true
            return
        }
        val sealed = if (key != null) SealedFile.sealChecked(key, plain) else null
        SealedFile.writeAtomic(f, File(f.parentFile, "$FILE.tmp"), sealed ?: plain)
    }

    private fun encode(list: List<String>): ByteArray = (list.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): List<String> = try {
        bytes.inputStream().bufferedReader(Charsets.UTF_8).readLines().takeLast(MAX)
    } catch (e: Throwable) {
        emptyList()
    }

    private const val FILE = "activity.log"
    private const val ASIDE_PREFIX = "activity.log.unreadable-"
}
