package com.relaypony.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.relaypony.session.pairing.SasV2
import com.relaypony.session.wan.RelayUrls
import kotlinx.coroutines.delay
import java.io.File

/** The graphical app: a device you can leave open to send and receive, on the reused core. */
fun cmdGui() {
    setDockIcon()   // best-effort macOS dock icon while running
    I18n.init(DesktopSettings().lang)
    application {
        val controller = remember { DesktopController() }
        var themeMode by remember { mutableStateOf(Config.loadTheme()) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "RelayPony",
            icon = painterResource("relaypony.png"),
            state = rememberWindowState(width = 940.dp, height = 660.dp),
        ) {
            val dark = resolveDark(themeMode)
            LaunchedEffect(dark) { matchTitleBar(window, dark) }
            RelayPonyTheme(dark) {
                AppShell(controller, themeMode) { mode ->
                    themeMode = mode
                    Config.saveTheme(mode)
                }
            }
        }
    }
}

/** macOS draws the title bar itself; keep it in step when the app's theme differs from the system's. */
private fun matchTitleBar(window: javax.swing.RootPaneContainer, dark: Boolean) {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("mac")) return
    runCatching {
        window.rootPane.putClientProperty(
            "apple.awt.windowAppearance",
            if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
        )
    }
}

private fun setDockIcon() {
    runCatching {
        val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream("relaypony.png") ?: return
        val image = javax.imageio.ImageIO.read(stream) ?: return
        if (java.awt.Taskbar.isTaskbarSupported()) java.awt.Taskbar.getTaskbar().iconImage = image
    }
}

@Composable
internal fun PassphraseDialog(
    title: String,
    help: String,
    confirmLabel: String,
    requireConfirmation: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = requireConfirmation && confirm.isNotEmpty() && pass != confirm
    val ok = pass.isNotEmpty() && (!requireConfirmation || pass == confirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(help, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, singleLine = true,
                    label = { Text(tr("set_backup_pass_label")) }, visualTransformation = PasswordVisualTransformation(),
                )
                if (requireConfirmation) {
                    OutlinedTextField(
                        value = confirm, onValueChange = { confirm = it }, singleLine = true,
                        label = { Text(tr("d_pass_confirm")) }, visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (mismatch) Text(tr("d_pass_mismatch"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onConfirm(pass) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("pair_cancel")) } },
    )
}

@Composable
internal fun SectionDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

// --- desktop glue ---

internal fun qrImageBitmap(text: String, scale: Int = 6): ImageBitmap {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
    )
    val w = matrix.width
    val h = matrix.height
    val img = java.awt.image.BufferedImage(w * scale, h * scale, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = java.awt.Color.WHITE
    g.fillRect(0, 0, w * scale, h * scale)
    g.color = java.awt.Color.BLACK
    for (y in 0 until h) for (x in 0 until w) if (matrix.get(x, y)) g.fillRect(x * scale, y * scale, scale, scale)
    g.dispose()
    return img.toComposeImageBitmap()
}

internal fun pickFiles(): List<File> {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, I18n.t("d_pick_files"), java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files?.toList() ?: emptyList()
}

internal fun pickSaveFile(defaultName: String, title: String): File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
    d.file = defaultName
    d.isVisible = true
    val dir = d.directory
    val name = d.file
    return if (dir != null && name != null) File(dir, name) else null
}

internal fun pickOpenFile(title: String): File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    d.isVisible = true
    val dir = d.directory
    val name = d.file
    return if (dir != null && name != null) File(dir, name) else null
}

internal fun openFile(file: File) {
    runCatching { java.awt.Desktop.getDesktop().open(file) }
}

internal fun openLink(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

/** A folder to send. macOS gets its native folder picker; elsewhere Swing's chooser in folder mode. */
internal fun pickFolder(): File? {
    val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    if (mac) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val d = java.awt.FileDialog(null as java.awt.Frame?, I18n.t("d_pick_folder"), java.awt.FileDialog.LOAD)
            d.isVisible = true
            val dir = d.directory
            val name = d.file
            return if (dir != null && name != null) File(dir, name) else null
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    val chooser = javax.swing.JFileChooser().apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = I18n.t("d_pick_folder")
    }
    return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
}

// --- Wi-Fi Direct ---------------------------------------------------------------------------

/**
 * Wi-Fi Direct: the two radios talk to each other with no network in between — no router, no
 * hotspot, no shared Wi-Fi at all. It is also the most environment-dependent thing in the app, so
 * this screen leads with a capability check and shows its working when something fails.
 */
@Composable
internal fun DirectScreen(c: DesktopController) {
    LaunchedEffect(Unit) { if (c.p2pReadiness == null) c.p2pCheck() }
    var showLog by remember { mutableStateOf(false) }
    val readiness = c.p2pReadiness

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            tr("d_direct_body"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            readiness == null -> Text(tr("d_checking"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !readiness.usable -> {
                Text(tr("d_direct_unavailable"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                readiness.blockers.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                readiness.notes.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { c.p2pCheck() }) { Text(tr("d_check_again")) }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !c.p2pBusy, onClick = { c.p2pFind() }) {
                        Text(if (c.p2pBusy) tr("d_working") else tr("d_find_devices"))
                    }
                    OutlinedButton(enabled = c.p2pBusy, onClick = { c.p2pStop() }) { Text(tr("xfer_stop")) }
                }
                if (c.p2pBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                c.sendProgress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }

                if (c.p2pPeers.isEmpty()) {
                    Text(
                        tr("d_direct_empty"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                c.p2pPeers.forEach { peer ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.name, style = MaterialTheme.typography.titleSmall)
                                Text(peer.deviceAddress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(enabled = !c.p2pBusy, onClick = {
                                val files = pickFiles()
                                if (files.isNotEmpty()) c.p2pTransfer(peer, files)
                            }) { Text(tr("d_direct_send")) }
                            TextButton(enabled = !c.p2pBusy, onClick = { c.p2pTransfer(peer, emptyList()) }) {
                                Text(tr("d_direct_receive"))
                            }
                        }
                    }
                }
            }
        }

        if (c.p2pStatus.isNotEmpty()) {
            Text(c.p2pStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (c.p2pTranscript.isNotEmpty()) {
            TextButton(onClick = { showLog = !showLog }) {
                Text(if (showLog) tr("d_hide_log") else tr("d_show_log"))
            }
            if (showLog) {
                // Verbatim, in order — this is what belongs in a bug report when P2P misbehaves.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    c.p2pTranscript.forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
