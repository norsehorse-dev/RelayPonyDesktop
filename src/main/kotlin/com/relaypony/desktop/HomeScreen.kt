package com.relaypony.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/** Home (plan section 4.2): whether this computer can receive, then what to send. */
@Composable
fun HomeScreen(c: DesktopController) {
    var showText by remember { mutableStateOf(false) }
    Page {
        ReadyStatus(c)
        ListDivider()
        ActiveSend(c)
        Staged(c)
        FolderPrep(c)
        ListRow(tr("home_files"), icon = Icons.Filled.Description, onClick = {
            pickFiles().takeIf { it.isNotEmpty() }?.let { c.stageFiles(it) }
        })
        ListDivider()
        ListRow(tr("home_folder"), icon = Icons.Filled.Folder, onClick = {
            if (c.folderPrep == null) pickFolder()?.let { c.stageFolder(it) }
        })
        ListDivider()
        ListRow(tr("home_text"), icon = Icons.Filled.TextFields, onClick = { showText = true })
        ListDivider()
        Text(tr("home_hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
    if (showText) TextDropDialog(c) { showText = false }
}

/** "Ready to receive", or why not, with the addresses behind Details. */
@Composable
private fun ReadyStatus(c: DesktopController) {
    var details by remember { mutableStateOf(false) }
    val lan = c.receiving && c.reachableAt.isNotEmpty()
    val receivingNow = c.receiveProgress != null
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    receivingNow -> StatusText(tr("home_receiving"), Icons.Filled.Download, MaterialTheme.colorScheme.primary)
                    lan -> StatusText(tr("home_ready"), Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary)
                    c.receiving -> StatusText(tr("home_ready_internet"), Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary)
                    else -> StatusText(tr("home_not_ready"), Icons.Filled.PauseCircle, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { details = true }) { Text(tr("d_details")) }
        }
        c.receiveProgress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
        when {
            !c.receiving -> Text(tr("d_receiving_off"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !lan -> Text(tr("d_no_network"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(tr("ob_this_device", c.deviceName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.reachableAt.isEmpty()) {
                        Text(tr("home_no_addresses"), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(tr("d_reach_title"), style = MaterialTheme.typography.titleSmall)
                        c.reachableAt.forEach { Text(it, style = MonoStyle) }
                        Text(tr("d_reach_hint"), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        if (c.receiving) c.wanReceiveStatus.ifEmpty { tr("rec_wan_on") } else tr("rec_wan_off"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { details = false }) { Text(tr("pair_ok")) } },
        )
    }
}

/** A send running behind Home, one click from the transfer screen. */
@Composable
private fun ActiveSend(c: DesktopController) {
    val batch = c.batch ?: return
    if (c.transferVisible) return
    Column(
        Modifier.fillMaxWidth().clickable { c.transferVisible = true }.padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(tr(if (batch.active) "home_sending" else "home_send_done"), style = MaterialTheme.typography.bodyLarge)
        Text(
            batch.legs.joinToString(", ") { it.name },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (batch.active) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    ListDivider()
}

/** A folder being zipped before it can be sent (PROTOCOL_v3 section 12.1). */
@Composable
private fun FolderPrep(c: DesktopController) {
    val prep = c.folderPrep ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (prep.name.isEmpty()) tr("home_preparing_plain") else tr("home_preparing", prep.name),
            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (prep.total > 0) {
            LinearProgressIndicator(progress = { (prep.done.toFloat() / prep.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${humanSize(prep.done)} / ${humanSize(prep.total)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        TextButton(onClick = { c.cancelFolder() }) { Text(tr("pair_cancel")) }
    }
    ListDivider()
}

/** Content picked but not sent yet, for when the Send-to dialog was closed. */
@Composable
private fun Staged(c: DesktopController) {
    if (c.staged.isEmpty() || c.sendToOpen || c.batch != null) return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("home_staged", c.staged.size, humanSize(c.staged.sumOf { it.size })), style = MaterialTheme.typography.bodyLarge)
        c.staged.take(3).forEach { f ->
            Text(f.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = { c.sendToOpen = true }) { Text(tr("home_choose_device")) }
            TextButton(onClick = { c.clearStaged() }) { Text(tr("send_clear")) }
        }
    }
    ListDivider()
}

/** Text to send, with the clipboard offered when it holds text (plan section 5). */
@Composable
private fun TextDropDialog(c: DesktopController, onDismiss: () -> Unit) {
    val clip = remember {
        runCatching {
            val cb = Toolkit.getDefaultToolkit().systemClipboard
            if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) (cb.getData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("home_text_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(160.dp))
                if (clip != null && text.isEmpty()) TextButton(onClick = { text = clip }) { Text(tr("home_text_from_clipboard")) }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { c.stageText(text); onDismiss() }) { Text(tr("home_text_next")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("pair_cancel")) } },
    )
}
