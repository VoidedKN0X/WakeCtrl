package com.vknxworks.wakectrl.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vknxworks.wakectrl.BuildConfig
import com.vknxworks.wakectrl.R
import com.vknxworks.wakectrl.container
import com.vknxworks.wakectrl.data.settings.BackupFormatException
import com.vknxworks.wakectrl.data.settings.SettingsBackups
import com.vknxworks.wakectrl.data.settings.WakeBackup
import com.vknxworks.wakectrl.ui.components.ConfirmDialog
import com.vknxworks.wakectrl.ui.components.Group
import com.vknxworks.wakectrl.ui.components.GroupRow
import com.vknxworks.wakectrl.ui.lock.AppLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Export and import: WakeCtrl's settings and automations as a JSON file in a place you pick. The app lock, the
 * privacy settings and "Allow other apps" are not in the file and an import never changes them.
 */
@Composable
fun BackupGroup() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<WakeBackup?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = try {
                val text = SettingsBackups.export(c.settings.settings.value, BuildConfig.VERSION_NAME, OffsetDateTime.now().toString())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IOException()
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            Toast.makeText(context, if (ok) R.string.backup_saved else R.string.backup_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > SettingsBackups.MAX_BYTES) throw BackupFormatException(newer = false)
                        }
                        out.toString(Charsets.UTF_8.name())
                    } ?: throw IOException()
                }
                pending = withContext(Dispatchers.Default) { SettingsBackups.parse(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupFormatException) {
                Toast.makeText(context, if (e.newer) R.string.backup_too_new else R.string.backup_not_valid, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(context, R.string.backup_read_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    Group(stringResource(R.string.backup_title), footer = stringResource(R.string.backup_footer)) {
        GroupRow(
            stringResource(R.string.backup_save),
            stringResource(R.string.backup_save_sub),
            Icons.Outlined.FileDownload,
            onClick = { AppLock.expectReturn(); save.launch("wakectrl-settings-${LocalDate.now()}.json") },
        )
        GroupRow(
            stringResource(R.string.backup_restore),
            stringResource(R.string.backup_restore_sub),
            Icons.Outlined.FileUpload,
            onClick = { AppLock.expectReturn(); open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        )
    }

    pending?.let { b ->
        val result = remember(b) { SettingsBackups.apply(b, c.settings.settings.value) }
        val apps = pluralStringResource(R.plurals.apps_selected, result.appWakePackages.size, result.appWakePackages.size)
        val devices = pluralStringResource(R.plurals.devices_selected, result.btWakeDevices.size, result.btWakeDevices.size)
        val windows = pluralStringResource(R.plurals.backup_windows, result.scheduleWindows.size, result.scheduleWindows.size)
        ConfirmDialog(
            stringResource(R.string.backup_restore_title),
            stringResource(R.string.backup_restore_body, b.createdAt?.take(10) ?: "?", "$apps\n$devices\n$windows"),
            stringResource(R.string.backup_restore_confirm),
            onDismiss = { pending = null },
        ) {
            pending = null
            scope.launch {
                // Applied to the settings as they are at that moment, so a change made meanwhile isn't lost.
                c.settings.update { SettingsBackups.apply(b, it) }
                c.refreshWake()
                Toast.makeText(context, R.string.backup_restored, Toast.LENGTH_LONG).show()
            }
        }
    }
}
