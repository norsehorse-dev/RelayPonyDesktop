package com.relaypony.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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

/** Received (plan section 4.5): refused senders first, then what has arrived, newest first. */
@Composable
fun ReceivedScreen(c: DesktopController, onPair: () -> Unit) {
    var deleting by remember { mutableStateOf<DesktopController.ReceivedItem?>(null) }
    Page {
        if (c.requests.isNotEmpty()) {
            c.requests.forEach { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("req_title", r.name.ifEmpty { "?" }), style = MaterialTheme.typography.bodyLarge)
                    Text(tr("req_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onPair) { Text(tr("send_pair_peer")) }
                        TextButton(onClick = { c.dismissRequest(r) }) { Text(tr("req_dismiss")) }
                    }
                }
                ListDivider()
            }
        }
        c.receiveProgress?.let { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("rec_receiving"), style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
            ListDivider()
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionHeader(if (c.received.isEmpty()) tr("nav_received") else tr("inbox_header", c.received.size))
            }
            TextButton(onClick = { c.openInbox() }) { Text(tr("d_open_folder")) }
        }
        if (c.received.isEmpty()) {
            Text(tr("d_inbox_empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        c.received.forEach { item ->
            ReceivedRow(c, item, onDelete = { deleting = item })
            ListDivider()
        }
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("inbox_delete_title")) },
            text = { Text(tr("d_inbox_delete_msg", item.file.name)) },
            confirmButton = { TextButton(onClick = { c.deleteReceived(item); deleting = null }) { Text(tr("inbox_delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("inbox_cancel")) } },
        )
    }
}

@Composable
private fun ReceivedRow(c: DesktopController, item: DesktopController.ReceivedItem, onDelete: () -> Unit) {
    val file = item.file
    val drop = remember(file.path, file.length()) { c.textDrop(file) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val size = humanSize(file.length())
        Text(
            if (item.from.isEmpty()) size else tr("inbox_size_from", size, item.from),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (drop) {
            is ClipText.Sniff.Link -> Text(drop.url, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            is ClipText.Sniff.Text -> Text(drop.text.trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            null -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (drop) {
                is ClipText.Sniff.Link -> {
                    TextButton(onClick = { QrInput.copy(drop.url); c.notice = I18n.t("inbox_copied") }) { Text(tr("inbox_copy")) }
                    TextButton(onClick = { openLink(drop.url) }) { Text(tr("inbox_open_link")) }
                }
                is ClipText.Sniff.Text -> TextButton(onClick = { QrInput.copy(drop.text); c.notice = I18n.t("inbox_copied") }) { Text(tr("inbox_copy")) }
                null -> Unit
            }
            if (FolderExtractor.isArchive(file.name)) {
                if (c.extracting == file.path) Text(tr("inbox_extracting"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                else TextButton(enabled = c.extracting == null, onClick = { c.extractFolder(file) }) { Text(tr("inbox_extract")) }
            }
            TextButton(onClick = { openFile(file) }) { Text(tr("inbox_open")) }
            TextButton(onClick = onDelete) { Text(tr("inbox_delete")) }
        }
    }
}
