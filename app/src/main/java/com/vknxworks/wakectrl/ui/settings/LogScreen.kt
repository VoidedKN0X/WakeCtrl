package com.vknxworks.wakectrl.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.wakectrl.R
import com.vknxworks.wakectrl.ui.components.CtrlSecondaryButton
import com.vknxworks.wakectrl.ui.components.CtrlSquareButton
import com.vknxworks.wakectrl.ui.components.MessageView
import com.vknxworks.wakectrl.ui.components.ScreenTopBar
import com.vknxworks.wakectrl.ui.components.WarningCard
import com.vknxworks.wakectrl.ui.theme.CtrlColors
import com.vknxworks.wakectrl.ui.theme.CtrlType
import com.vknxworks.wakectrl.ui.theme.Gutter
import com.vknxworks.wakectrl.wake.ActivityLog

/** What WakeCtrl did and why, newest first. Kept on the phone only. */
@Composable
fun LogScreen() {
    val entries by ActivityLog.entries.collectAsStateWithLifecycle()
    val ready by ActivityLog.ready.collectAsStateWithLifecycle()
    val unreadable by ActivityLog.unreadable.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.activity_log)) {
            if (entries.isNotEmpty()) {
                CtrlSquareButton(Icons.Outlined.DeleteSweep, stringResource(R.string.clear), onClick = { ActivityLog.clear() })
            }
        }
        if (unreadable) {
            // The stored log is encrypted and can't be opened. It is kept as it is; nothing is written to it.
            WarningCard(
                stringResource(R.string.log_unreadable),
                Icons.Outlined.ReceiptLong,
                Modifier.padding(horizontal = Gutter, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CtrlSecondaryButton(onClick = { ActivityLog.retry() }) { Text(stringResource(R.string.log_try_again)) }
                CtrlSecondaryButton(onClick = { ActivityLog.startNew() }) { Text(stringResource(R.string.log_start_new)) }
            }
        }
        if (entries.isEmpty()) {
            // Not while the stored log is still being read, and not next to the "can't open" card.
            if (ready && !unreadable) {
                MessageView(Icons.Outlined.ReceiptLong, stringResource(R.string.log_empty), stringResource(R.string.log_empty_body))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(entries.asReversed()) { line ->
                    Text(line, style = CtrlType.Data, color = CtrlColors.Text, modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp))
                }
            }
        }
    }
}
