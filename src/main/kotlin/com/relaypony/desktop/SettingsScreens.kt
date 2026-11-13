package com.relaypony.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.relaypony.session.pairing.PinnedDevice
import java.io.File
import java.net.URLEncoder

/** Every external link, as Android's AppLinks, plus the desktop repository. */
private object Links {
    const val PONY_FAMILY = "https://pony.norsehor.se"
    const val PGPONY = "https://pgpony.app"
    const val AGEPONY = "https://agepony.com"
    const val QUORUMPONY = "https://quorumpony.com"
    const val CARRIERPONY = "https://carrierpony.com"
    const val BURNPONY = "https://burnpony.app"
    const val VAULTPONY = "https://vaultpony.app"
    const val PASSPONY = "https://passpony.app"
    const val SCRUBPONY = "https://scrubpony.app"
    const val REPO = "https://github.com/norsehorse-dev/RelayPonyDesktop"
    const val CORE_REPO = "https://github.com/norsehorse-dev/RelayPonyCore"
    const val RELAY_REPO = "https://github.com/norsehorse-dev/RelayPony-Relay"
    const val SUPPORT = "https://relaypony.app/support"
    const val PRIVACY = "https://relaypony.app/privacy"
    const val SECURITY = "https://relaypony.app/security"
    const val SELF_HOST = "https://relaypony.app/self-host"
    const val OPEN_SOURCE = "https://relaypony.app/open-source"
    const val FEEDBACK_EMAIL = "norsehorse@norsehor.se"
}

@Composable
fun SettingsScreen(c: DesktopController, themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit, onAdvanced: () -> Unit) {
    var langPicker by remember { mutableStateOf(false) }
    var themePicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Page {
        SectionHeader(tr("set_language"))
        ListRow(tr("set_app_language"), I18n.languages.firstOrNull { it.first == I18n.lang }?.second, onClick = { langPicker = true })
        ListDivider()
        ListRow(tr("set_theme"), tr(themeMode.labelKey), onClick = { themePicker = true })

        SectionHeader(tr("set_this_device"))
        ListRow(tr("set_name", c.deviceName), onClick = { renaming = true })

        SectionHeader(tr("set_help_header"))
        ListRow(tr("set_help_faq_t"), tr("set_help_faq_d"), onClick = { openLink(Links.SUPPORT) })
        ListDivider()
        ListRow(tr("set_help_feedback_t"), tr("set_help_feedback_d"), onClick = {
            val subject = URLEncoder.encode("RelayPony Desktop Feedback (${c.appVersion})", "UTF-8").replace("+", "%20")
            openLink("mailto:${Links.FEEDBACK_EMAIL}?subject=$subject")
        })
        ListDivider()
        ListRow(tr("set_help_privacy_t"), tr("set_help_privacy_d"), onClick = { openLink(Links.PRIVACY) })
        ListDivider()
        ListRow(tr("set_help_security_t"), tr("set_help_security_d"), onClick = { openLink(Links.SECURITY) })

        SectionHeader(tr("set_more_header"))
        listOf(
            Triple("set_more_pgpony", "set_more_pgpony_d", Links.PGPONY),
            Triple("set_more_agepony", "set_more_agepony_d", Links.AGEPONY),
            Triple("set_more_quorumpony", "set_more_quorumpony_d", Links.QUORUMPONY),
            Triple("set_more_carrierpony", "set_more_carrierpony_d", Links.CARRIERPONY),
            Triple("set_more_burnpony", "set_more_burnpony_d", Links.BURNPONY),
            Triple("set_more_vaultpony", "set_more_vaultpony_d", Links.VAULTPONY),
            Triple("set_more_passpony", "set_more_passpony_d", Links.PASSPONY),
            Triple("set_more_scrubpony", "set_more_scrubpony_d", Links.SCRUBPONY),
            Triple("set_more_family_t", "set_more_family_d", Links.PONY_FAMILY),
            Triple("d_more_appsrc_t", "d_more_appsrc_d", Links.REPO),
            Triple("set_more_core_t", "set_more_core_d", Links.CORE_REPO),
        ).forEachIndexed { i, (t, d, url) ->
            if (i > 0) ListDivider()
            ListRow(tr(t), tr(d), onClick = { openLink(url) })
        }

        SectionHeader(tr("set_about"))
        Text(tr("set_about_desc"), style = MaterialTheme.typography.bodyLarge)
        Text(tr("d_about_version", c.appVersion), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tr("d_about_compat"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tr("set_about_encryption"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tr("set_about_transport"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ListRow(tr("set_about_licenses_t"), tr("set_about_licenses_d"), onClick = { openLink(Links.OPEN_SOURCE) })
        ListDivider()
        ListRow(tr("set_replay"), onClick = { c.replayOnboarding() })

        SectionHeader(tr("adv_title"))
        ListRow(tr("adv_title"), tr("d_adv_desc"), onClick = onAdvanced)
    }

    if (langPicker) {
        ChoiceDialog(
            title = tr("set_app_language"),
            options = I18n.languages.map { it.first to it.second },
            selected = I18n.lang,
            onSelect = { c.setLanguage(it) },
            onDismiss = { langPicker = false },
        )
    }
    if (themePicker) {
        ChoiceDialog(
            title = tr("set_theme"),
            options = ThemeMode.entries.map { it.name to tr(it.labelKey) },
            selected = themeMode.name,
            onSelect = { onThemeChange(ThemeMode.valueOf(it)) },
            onDismiss = { themePicker = false },
        )
    }
    if (renaming) RenameDialog(c) { renaming = false }
}

/** A single choice from a list: radio rows, the whole row selects (long labels never truncate). */
@Composable
fun ChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (key, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = key == selected, role = Role.RadioButton, onClick = { onSelect(key) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = key == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("set_close")) } },
    )
}

@Composable
private fun RenameDialog(c: DesktopController, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(c.deviceName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("set_this_device")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("d_name_help"), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(tr("d_name_label")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { c.rename(name); onDismiss() }) { Text(tr("set_relay_save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("pair_cancel")) } },
    )
}

private enum class BackupAction(val titleKey: String, val helpKey: String, val actionKey: String, val export: Boolean) {
    EXPORT_IDENTITY("set_backup_export_identity", "set_backup_pass_export", "set_backup_export_action", true),
    IMPORT_IDENTITY("set_backup_import_identity", "set_backup_pass_import", "set_backup_import_action", false),
    EXPORT_ADDRESSES("set_backup_export_addresses", "set_backup_pass_export", "set_backup_export_action", true),
    IMPORT_ADDRESSES("set_backup_import_addresses", "set_backup_pass_import", "set_backup_import_action", false),
}

@Composable
fun AdvancedScreen(c: DesktopController, onPair: () -> Unit, onDirect: () -> Unit) {
    var byAddress by remember { mutableStateOf(false) }
    var unpairing by remember { mutableStateOf<PinnedDevice?>(null) }
    var rotating by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf<Pair<BackupAction, File>?>(null) }
    var relayField by remember(c.relayServer) { mutableStateOf(c.relayServer) }
    val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    Page {
        SectionHeader(tr("d_receiving_header"))
        SwitchRow(tr("d_receive_toggle"), tr("d_receive_toggle_d"), c.receiving) { on -> if (on) c.startReceiving() else c.stopReceiving() }
        ListDivider()
        SwitchRow(tr("adv_accept_unpaired"), tr("adv_accept_unpaired_d"), c.acceptUnpaired) { c.setAcceptUnpairedSetting(it) }

        SectionHeader(tr("set_paired_header"))
        if (c.pairedDevices.isEmpty()) {
            Text(tr("d_paired_empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onPair) { Text(tr("send_pair_new")) }
        }
        c.pairedDevices.forEachIndexed { i, device ->
            if (i > 0) ListDivider()
            ListRow(device.name, device.recipientHandle.take(20) + "…", trailing = {
                TextButton(onClick = { unpairing = device }) { Text(tr("set_paired_remove")) }
            })
        }

        SectionHeader(tr("adv_connections"))
        if (linux) {
            ListRow(tr("d_direct_title"), tr("d_direct_row_d"), onClick = onDirect)
            ListDivider()
        }
        ListRow(tr("send_by_address_title"), tr("d_by_address_row_d"), onClick = { byAddress = true })
        ListDivider()
        ListRow(tr("d_search_again"), tr("d_search_again_d"), onClick = { c.rescan() }, trailing = {
            if (c.scanning) LinearProgressIndicator(Modifier.width(48.dp))
        })

        SectionHeader(tr("d_reach_title"))
        if (c.reachableAt.isEmpty()) {
            Text(tr("home_no_addresses"), style = MaterialTheme.typography.bodyMedium)
        } else {
            SelectionContainer { Column { c.reachableAt.forEach { Text(it, style = MonoStyle) } } }
            Text(tr("d_reach_hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        c.networkProblems.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

        SectionHeader(tr("adv_verify_title"))
        Text(tr("adv_verify_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val unpairedNearby = c.peers.filter { !c.isPaired(it.recipientHandle) }
        if (unpairedNearby.isEmpty()) {
            Text(tr("adv_verify_none"), style = MaterialTheme.typography.bodyMedium)
        }
        unpairedNearby.forEachIndexed { i, peer ->
            if (i > 0) ListDivider()
            ListRow(peer.name, trailing = { TextButton(onClick = { c.stageDiscovered(peer) }) { Text(tr("verify_action")) } })
        }

        SectionHeader(tr("set_relay_header"))
        Text(tr("set_relay_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = relayField, onValueChange = { relayField = it }, singleLine = true,
            label = { Text(tr("set_relay_url_label")) }, placeholder = { Text("https://relaypony.app") },
            textStyle = MonoStyle, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = relayField.trim() != c.relayServer, onClick = { c.setRelay(relayField) }) { Text(tr("set_relay_save")) }
            TextButton(enabled = c.relayServer.isNotEmpty(), onClick = { relayField = ""; c.setRelay("") }) { Text(tr("set_relay_reset")) }
        }
        Text(
            tr(
                when (c.relayHasMailboxes) {
                    null -> "d_relay_checking"
                    true -> "d_relay_ok"
                    false -> "d_relay_old"
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (c.relayHasMailboxes == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ListRow(tr("set_relay_selfhost_t"), tr("set_relay_selfhost_d"), onClick = { openLink(Links.SELF_HOST) })
        ListDivider()
        ListRow(tr("set_relay_repo_t"), tr("set_relay_repo_d"), onClick = { openLink(Links.RELAY_REPO) })

        SectionHeader(tr("adv_identity"))
        Text(tr("adv_key_label"), style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(c.myHandle, style = MonoStyle) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { QrInput.copy(c.myHandle); c.notice = I18n.t("inbox_copied") }) { Text(tr("pair_copy")) }
            TextButton(onClick = { rotating = true }) { Text(tr("adv_rotate_inbox")) }
        }

        SectionHeader(tr("set_backup_header"))
        Text(tr("set_backup_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (c.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        BackupAction.entries.forEachIndexed { i, action ->
            if (i > 0) ListDivider()
            ListRow(tr(action.titleKey), onClick = {
                if (c.busy) return@ListRow
                val file = if (action.export) {
                    pickSaveFile(if (action == BackupAction.EXPORT_IDENTITY) "relaypony-identity.age" else "relaypony-addresses.age", I18n.t(action.titleKey))
                } else {
                    pickOpenFile(I18n.t(action.titleKey))
                }
                if (file != null) backup = action to file
            })
        }
    }

    if (byAddress) SendByAddressDialog(c) { byAddress = false }
    unpairing?.let { device ->
        AlertDialog(
            onDismissRequest = { unpairing = null },
            title = { Text(tr("set_unpair_title", device.name)) },
            text = { Text(tr("set_unpair_body", device.name)) },
            confirmButton = { TextButton(onClick = { c.unpair(device.recipientHandle); unpairing = null }) { Text(tr("set_unpair_confirm"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { unpairing = null }) { Text(tr("pair_cancel")) } },
        )
    }
    if (rotating) {
        AlertDialog(
            onDismissRequest = { rotating = false },
            title = { Text(tr("adv_rotate_inbox")) },
            text = { Text(tr("adv_rotate_body")) },
            confirmButton = { TextButton(onClick = { c.rotateInbox(); rotating = false }) { Text(tr("adv_rotate_confirm")) } },
            dismissButton = { TextButton(onClick = { rotating = false }) { Text(tr("pair_cancel")) } },
        )
    }
    backup?.let { (action, file) ->
        PassphraseDialog(
            title = tr(action.titleKey),
            help = tr(action.helpKey),
            confirmLabel = tr(action.actionKey),
            requireConfirmation = action.export,
            onDismiss = { backup = null },
            onConfirm = { pass ->
                backup = null
                when (action) {
                    BackupAction.EXPORT_IDENTITY -> c.exportIdentity(pass, file)
                    BackupAction.IMPORT_IDENTITY -> c.importIdentity(pass, file)
                    BackupAction.EXPORT_ADDRESSES -> c.exportAddresses(pass, file)
                    BackupAction.IMPORT_ADDRESSES -> c.importAddresses(pass, file)
                }
            },
        )
    }
}

/** A setting that's on or off: the whole row toggles, the switch only shows the state. */
@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
