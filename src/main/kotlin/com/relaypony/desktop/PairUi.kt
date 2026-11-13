package com.relaypony.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.relaypony.session.pairing.SasV2
import com.relaypony.session.wan.RelayUrls
import kotlinx.coroutines.delay

/** Pair a device (PROTOCOL_v3 sections 3, 4 and 7): QR code or word code. */
@Composable
fun PairDialog(c: DesktopController, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val close = { c.pairing.dialogClosed(); onDismiss() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("pair_title")) },
        text = {
            Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("pair_tab_qr")) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("pair_tab_word")) })
                }
                if (tab == 0) QrTab(c) else WordTab(c)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(tr("set_close")) } },
    )
}

/** The live pairing QR (QR v2): good for five minutes and one use, then replaced. */
@Composable
private fun QrTab(c: DesktopController) {
    var legacy by remember { mutableStateOf(false) }
    var readOpen by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        c.pairing.surfaceVisible(true)
        onDispose { c.pairing.surfaceVisible(false) }
    }
    var remaining by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            c.pairing.ensureQr()
            remaining = c.pairing.qrRemainingMs()
            delay(1000)
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(if (legacy) "pair_qr_legacy_body" else "pair_qr_body"), style = MaterialTheme.typography.bodyMedium)
        val text = if (legacy) c.legacyQrText() else c.pairing.qr?.encode()
        if (text != null) {
            val bitmap = remember(text) { qrImageBitmap(text) }
            // QR codes need dark modules on white in either theme, or phones can't read them.
            Box(Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)) {
                Image(bitmap = bitmap, contentDescription = tr("rec_qr_desc"), modifier = Modifier.size(200.dp))
            }
        }
        Text(tr("ob_this_device", c.deviceName), style = MaterialTheme.typography.bodyMedium)
        if (!legacy) {
            val secs = remaining / 1000
            Text(tr("pair_qr_expires", "%d:%02d".format(secs / 60, secs % 60)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { c.pairing.refreshQr() }) { Text(tr("pair_qr_refresh")) }
        }
        TextButton(onClick = { legacy = !legacy }) { Text(tr(if (legacy) "pair_qr_show_new" else "pair_qr_show_legacy")) }
        ListDivider()
        TextButton(onClick = { readOpen = !readOpen }) { Text(tr("d_read_link")) }
        if (readOpen) {
            Text(tr("d_read_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val code = QrInput.fromClipboard()
                    if (code == null) problem = I18n.t("d_read_no_clipboard") else { problem = null; c.pairing.onScanned(code) }
                }) { Text(tr("d_read_clipboard")) }
                OutlinedButton(onClick = {
                    val file = pickOpenFile(I18n.t("d_read_image")) ?: return@OutlinedButton
                    val code = QrInput.fromImageFile(file)
                    if (code == null) problem = I18n.t("d_read_no_qr", file.name) else { problem = null; c.pairing.onScanned(code) }
                }) { Text(tr("d_read_image")) }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Word codes (section 7): one device shows a short code from its relay, the other types it in. */
@Composable
private fun WordTab(c: DesktopController) {
    var code by remember { mutableStateOf("") }
    var ownRelay by remember { mutableStateOf(false) }
    var relay by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val w = c.pairing.word) {
            null -> {
                Text(tr("pair_word_intro"), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { c.pairing.showWordCode() }) { Text(tr("pair_word_show")) }
                ListDivider()
                Text(tr("pair_word_enter_label"), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = code, onValueChange = { code = it }, singleLine = true,
                    label = { Text(tr("pair_word_field")) }, placeholder = { Text(tr("pair_word_placeholder")) },
                    textStyle = MonoStyle, modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().toggleable(value = ownRelay, role = Role.Checkbox, onValueChange = { ownRelay = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ownRelay, onCheckedChange = null)
                    Text(tr("pair_word_relay_toggle"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
                if (ownRelay) {
                    OutlinedTextField(value = relay, onValueChange = { relay = it }, singleLine = true, label = { Text(tr("pair_word_relay_field")) }, modifier = Modifier.fillMaxWidth())
                }
                Button(enabled = code.isNotBlank(), onClick = { c.pairing.enterWordCode(code, if (ownRelay) relay else "") }) { Text(tr("pair_word_pair")) }
            }
            DesktopPairing.WordCode.Working -> {
                Text(tr("pair_word_working"), style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { c.pairing.cancelWordCode() }) { Text(tr("pair_cancel")) }
            }
            is DesktopPairing.WordCode.Showing -> {
                Text(tr("pair_word_your_code"), style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(w.code, style = MonoStyle.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
                if (w.relay.isNotEmpty()) Text(tr("pair_word_on_relay", RelayUrls.base(w.relay)), style = MonoStyle)
                Text(tr("pair_word_show_hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { QrInput.copy(w.code); c.notice = I18n.t("inbox_copied") }) { Text(tr("pair_copy")) }
                    TextButton(onClick = { c.pairing.cancelWordCode() }) { Text(tr("pair_cancel")) }
                }
                Text(tr("pair_word_waiting"), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is DesktopPairing.WordCode.Failed -> {
                Text(w.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                Button(onClick = { c.pairing.resetWordCode() }) { Text(tr("pair_try_again")) }
            }
        }
    }
}

/** The code comparison and its outcomes (PROTOCOL_v3 section 4.3). */
@Composable
fun PairPromptDialog(c: DesktopController, p: DesktopPairing.Prompt, onDone: () -> Unit) {
    val dismiss = { c.pairing.dismissPrompt(); onDone() }
    when (p) {
        is DesktopPairing.Prompt.Confirm -> {
            val name = p.pending.request.name.take(64)
            AlertDialog(
                onDismissRequest = {},
                title = { Text(tr("pair_confirm_title", name)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SasCode(p.pending.code)
                        Text(tr("pair_confirm_hint", name), style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = { Button(onClick = { c.pairing.answer(true) }) { Text(tr("pair_confirm_yes")) } },
                dismissButton = { TextButton(onClick = { c.pairing.answer(false) }) { Text(tr("pair_cancel")) } },
            )
        }
        is DesktopPairing.Prompt.Waiting -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("pair_waiting_title", p.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SasCode(p.code)
                    Text(tr("pair_waiting_hint", p.name), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { c.pairing.cancelWaiting() }) { Text(tr("pair_cancel")) } },
        )
        is DesktopPairing.Prompt.Paired -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(tr("pair_done_title", p.name)) },
            text = { Text(if (p.oneWay) tr("pair_done_one_way", p.name) else tr("pair_done_body")) },
            confirmButton = { TextButton(onClick = dismiss) { Text(tr("pair_done")) } },
        )
        else -> AlertDialog(
            onDismissRequest = dismiss,
            text = {
                Text(
                    when (p) {
                        is DesktopPairing.Prompt.Declined -> if (p.name.isEmpty()) tr("pair_declined_unnamed") else tr("pair_declined", p.name)
                        is DesktopPairing.Prompt.NoAnswer -> tr("pair_no_answer", p.name)
                        is DesktopPairing.Prompt.Problem -> p.text
                        is DesktopPairing.Prompt.UnpairedBy -> tr("pair_unpaired_by", p.name)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = { TextButton(onClick = dismiss) { Text(tr("pair_ok")) } },
        )
    }
}

/** The comparison code, large and in monospace so digits line up. */
@Composable
private fun SasCode(code: String) {
    Text(
        SasV2.display(code),
        style = MonoStyle.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Pair by code with a nearby RelayPony 3 device (one-way pins, as on Android). */
@Composable
fun VerifyDialog(c: DesktopController, pv: DesktopController.PendingVerify) {
    AlertDialog(
        onDismissRequest = { c.pendingVerify = null },
        title = { Text(tr("verify_title", pv.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("verify_code_label"), style = MaterialTheme.typography.titleSmall)
                Text(pv.sas, style = MonoStyle.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize), color = MaterialTheme.colorScheme.primary)
                Text(tr("verify_hint", pv.name), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { Button(onClick = { c.confirmVerify() }) { Text(tr("verify_pair")) } },
        dismissButton = { TextButton(onClick = { c.pendingVerify = null }) { Text(tr("pair_cancel")) } },
    )
}
