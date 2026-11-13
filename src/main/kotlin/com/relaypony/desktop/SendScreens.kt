package com.relaypony.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relaypony.session.pairing.PinnedDevice

/** Who gets the staged content (plan section 4.3). Nearby devices first; tick one or several. */
@Composable
fun SendToDialog(c: DesktopController, onPair: () -> Unit) {
    val nearby = c.peers.map { it.recipientHandle }.toSet()
    val targets = c.pairedDevices.sortedWith(compareByDescending<PinnedDevice> { it.recipientHandle in nearby }.thenBy { it.name.lowercase() })
    val ticked = remember {
        mutableStateListOf<String>().apply {
            addAll(c.sendToPreselect)
            if (isEmpty() && targets.size == 1) add(targets[0].recipientHandle)
        }
    }
    val close = { c.sendToOpen = false }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("sendto_title", c.staged.size, humanSize(c.staged.sumOf { it.size }))) },
        text = {
            Column(Modifier.widthIn(min = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                c.staged.take(3).forEach {
                    Text(it.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (c.staged.size > 3) Text(tr("sendto_more_files", c.staged.size - 3), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (targets.isEmpty()) {
                    Text(tr("sendto_no_devices"), style = MaterialTheme.typography.bodyLarge)
                } else if (targets.none { it.recipientHandle in nearby } && c.reachableAt.isNotEmpty()) {
                    Text(tr("sendto_isolation"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                targets.forEach { device ->
                    val h = device.recipientHandle
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(value = h in ticked, role = Role.Checkbox, onValueChange = { on -> if (on) ticked.add(h) else ticked.remove(h) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = h in ticked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(tr(if (h in nearby) "sendto_nearby" else "sendto_internet"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ListDivider()
                }
                TextButton(onClick = { close(); onPair() }) { Text(tr("send_pair_new")) }
            }
        },
        confirmButton = {
            Button(enabled = ticked.isNotEmpty(), onClick = { c.sendToDevices(ticked.toList()) }) {
                Text(tr("sendto_send_n", ticked.size.coerceAtLeast(1)))
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("pair_cancel")) } },
    )
}

/** Where each device's part of the send stands (plan section 4.4). */
@Composable
fun TransferScreen(c: DesktopController, onPair: () -> Unit) {
    val batch = c.batch ?: return
    Page {
        Text(
            tr(if (batch.active) "xfer_sending" else if (batch.allSent) "xfer_sent" else "xfer_finished"),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
        )
        Text(tr("xfer_files", batch.fileNames.size, humanSize(batch.totalBytes)), style = MaterialTheme.typography.titleMedium)
        batch.fileNames.take(5).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (batch.fileNames.size > 5) Text(tr("sendto_more_files", batch.fileNames.size - 5), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ListDivider()
        batch.legs.forEach { LegRow(c, it, onPair); ListDivider() }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (batch.active) {
                OutlinedButton(onClick = { c.stopSending() }) { Text(tr("xfer_stop")) }
                TextButton(onClick = { c.transferVisible = false }) { Text(tr("xfer_hide")) }
            } else {
                Button(onClick = { c.closeTransfer() }) { Text(tr("pair_done")) }
                TextButton(onClick = { c.sendMore() }) {
                    Text(if (batch.legs.size == 1) tr("xfer_send_more_one", batch.legs[0].name) else tr("xfer_send_more"))
                }
            }
        }
    }
}

@Composable
private fun LegRow(c: DesktopController, leg: SendLeg, onPair: () -> Unit) {
    var showDetail by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(leg.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    tr(
                        when (leg.route) {
                            SendRoute.NEARBY -> "xfer_route_nearby"
                            SendRoute.INTERNET -> "xfer_route_internet"
                            SendRoute.INTERNET_DIRECT -> "xfer_route_direct"
                            SendRoute.INTERNET_RELAY -> "xfer_route_relay"
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val label = when (leg.state) {
                LegState.CONNECTING -> tr("xfer_connecting")
                LegState.SENDING -> leg.progress?.let { tr("xfer_percent", (it * 100).toInt()) } ?: tr("xfer_in_progress")
                LegState.SENT -> tr("xfer_leg_sent")
                LegState.FAILED -> tr("xfer_leg_failed")
                LegState.CANCELLED -> tr("xfer_leg_stopped")
            }
            when (leg.state) {
                LegState.SENT -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
                LegState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
                else -> Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (leg.active) {
            val p = leg.progress
            if (p != null) LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (leg.state == LegState.FAILED) {
            Text(tr(if (leg.refused) "xfer_refused" else "xfer_unreachable", leg.name), style = MaterialTheme.typography.bodyMedium)
            if (!leg.refused && leg.route != SendRoute.NEARBY) {
                Text(tr("xfer_relay_hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (leg.refused) TextButton(onClick = { c.transferVisible = false; onPair() }) { Text(tr("send_pair_peer")) }
                TextButton(onClick = { c.retryLeg(leg.handle) }) { Text(tr("pair_try_again")) }
                if (!leg.error.isNullOrBlank()) TextButton(onClick = { showDetail = !showDetail }) { Text(tr(if (showDetail) "d_hide_details" else "d_details")) }
            }
            if (showDetail) leg.error?.let { SelectionContainer { Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

/**
 * Send to a paired device at an address typed by hand: the way out of networks discovery can't
 * cross. Pairing supplies the key, so only paired devices are offered.
 */
@Composable
fun SendByAddressDialog(c: DesktopController, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(c.pairedDevices.firstOrNull()) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(Config.listenPort.toString()) }
    val portNumber = port.trim().toIntOrNull()
    val ready = selected != null && host.isNotBlank() && portNumber != null && portNumber in 1..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("send_by_address_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (c.pairedDevices.isEmpty()) {
                    Text(tr("send_no_paired"), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(tr("d_by_address_help"), style = MaterialTheme.typography.bodyMedium)
                    Text(tr("send_by_address_device"), style = MaterialTheme.typography.titleSmall)
                    Column(Modifier.selectableGroup()) {
                        c.pairedDevices.forEach { device ->
                            val chosen = device.recipientHandle == selected?.recipientHandle
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 40.dp).selectable(selected = chosen, role = Role.RadioButton, onClick = { selected = device }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = chosen, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = host, onValueChange = { host = it }, singleLine = true, label = { Text(tr("send_by_address_host")) }, modifier = Modifier.weight(2f))
                        OutlinedTextField(value = port, onValueChange = { port = it }, singleLine = true, label = { Text(tr("send_by_address_port")) }, modifier = Modifier.weight(1f))
                    }
                    if (c.staged.isEmpty()) Text(tr("d_by_address_pick_next"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    val device = selected
                    val p = portNumber
                    if (device != null && p != null) {
                        if (c.staged.isEmpty()) pickFiles().takeIf { it.isNotEmpty() }?.let { c.stageFiles(it); c.sendToOpen = false }
                        if (c.staged.isNotEmpty()) c.sendToAddress(device, host, p)
                    }
                    onDismiss()
                },
            ) { Text(tr("send_by_address_go")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("pair_cancel")) } },
    )
}
