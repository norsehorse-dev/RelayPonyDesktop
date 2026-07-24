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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File

/** The graphical app: a device you can leave open to send and receive, on the reused core. */
fun cmdGui() {
    setDockIcon()   // best-effort macOS dock icon while running
    application {
        val controller = remember { DesktopController() }
        Window(
            onCloseRequest = ::exitApplication,
            title = "RelayPony",
            icon = painterResource("relaypony.png"),
            state = rememberWindowState(width = 940.dp, height = 660.dp),
        ) {
            RelayPonyTheme { App(controller) }
        }
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
private fun App(c: DesktopController) {
    LaunchedEffect(Unit) { c.startBrowsing() }
    var tab by remember { mutableStateOf(0) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            GradientHeader()
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Spacer(Modifier.height(12.dp))
                    NavigationRailItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Filled.Send, "Send") }, label = { Text("Send") })
                    NavigationRailItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Filled.Download, "Receive") }, label = { Text("Receive") })
                    NavigationRailItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Filled.Folder, "Files") }, label = { Text("Files") })
                    NavigationRailItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.Filled.Settings, "Settings") }, label = { Text("Settings") })
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when (tab) {
                        0 -> SendScreen(c)
                        1 -> ReceiveScreen(c)
                        2 -> FilesScreen(c)
                        else -> SettingsScreen(c)
                    }
                    if (c.status.isNotEmpty()) {
                        Text(
                            c.status,
                            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandViolet,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SendScreen(c: DesktopController) {
    var pairTarget by remember { mutableStateOf<DesktopDiscovery.Peer?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Send", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        c.sendProgress?.let { p ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text("Sending… ${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = BrandViolet)
            }
        }
        Text("Nearby devices", style = MaterialTheme.typography.titleSmall)
        if (c.peers.isEmpty()) {
            Text("Looking for nearby devices…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(c.peers) { peer ->
                val paired = peer.recipientHandle in c.pairedHandles
                Card(
                    Modifier.fillMaxWidth().clickable {
                        if (paired) {
                            val files = pickFiles()
                            if (files.isNotEmpty()) c.send(files, peer)
                        } else {
                            pairTarget = peer
                        }
                    }
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrustBadge(paired)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(peer.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (paired) "Paired · click to send files" else "Click to verify & pair",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (paired) BrandViolet else Color.Gray,
                            )
                        }
                        Text("wire v${peer.maxWire}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
            }
        }
    }
    pairTarget?.let { peer ->
        AlertDialog(
            onDismissRequest = { pairTarget = null },
            title = { Text("Pair with ${peer.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Verification code", style = MaterialTheme.typography.labelMedium)
                    Text(c.sasFor(peer), style = MaterialTheme.typography.headlineMedium, color = BrandViolet, fontWeight = FontWeight.Bold)
                    Text("Confirm this matches the six digits shown on ${peer.name}.", style = MaterialTheme.typography.bodySmall)
                    Text(peer.recipientHandle, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            },
            confirmButton = { TextButton(onClick = { c.pair(peer); pairTarget = null }) { Text("Pair") } },
            dismissButton = { TextButton(onClick = { pairTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReceiveScreen(c: DesktopController) {
    val qr = remember(c.qrPayload) { qrImageBitmap(c.qrPayload) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Receive", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (c.receiving) "Discoverable" else "Off", style = MaterialTheme.typography.bodyMedium, color = if (c.receiving) BrandViolet else Color.Gray)
            Spacer(Modifier.width(10.dp))
            Switch(checked = c.receiving, onCheckedChange = { on -> if (on) c.startReceiving() else c.stopReceiving() })
        }
        RadarPulse(active = c.receiving)
        Text("Scan to pair, then send to \"${c.deviceName}\"", style = MaterialTheme.typography.bodyMedium)
        Box(
            Modifier.border(3.dp, BrandGradient, RoundedCornerShape(14.dp)).padding(10.dp)
                .background(Color.White, RoundedCornerShape(6.dp)).padding(6.dp)
        ) {
            Image(bitmap = qr, contentDescription = "pairing QR", modifier = Modifier.size(210.dp))
        }
        Text(c.myHandle, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(
            if (c.receiving) "Listening on port ${c.listenPort} · files land in your Downloads"
            else "Turn on to receive files from paired devices.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
    }
}

@Composable
private fun FilesScreen(c: DesktopController) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Files", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (c.received.isEmpty()) {
            Text("Files people send you will appear here.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(c.received) { file ->
                Card(Modifier.fillMaxWidth().clickable { openFile(file) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.titleSmall)
                            Text(humanSize(file.length()), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Text("Open", color = BrandViolet, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(c: DesktopController) {
    var nameField by remember(c.deviceName) { mutableStateOf(c.deviceName) }
    val qr = remember(c.qrPayload) { qrImageBitmap(c.qrPayload) }
    var exportDest by remember { mutableStateOf<File?>(null) }
    var importFile by remember { mutableStateOf<File?>(null) }
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Text("Device name", style = MaterialTheme.typography.titleSmall)
        Text("How you appear to other devices when sending and pairing.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { c.rename(nameField) },
                enabled = nameField.isNotBlank() && nameField.trim() != c.deviceName,
            ) { Text("Save") }
        }

        SectionDivider()

        Text("Your pairing code", style = MaterialTheme.typography.titleSmall)
        Text("Show this to a phone to pair without a camera on this side.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Box(
            Modifier.border(3.dp, BrandGradient, RoundedCornerShape(14.dp)).padding(10.dp)
                .background(Color.White, RoundedCornerShape(6.dp)).padding(6.dp)
        ) {
            Image(bitmap = qr, contentDescription = "your pairing QR", modifier = Modifier.size(180.dp))
        }
        Text(c.myHandle, style = MaterialTheme.typography.labelSmall, color = Color.Gray)

        SectionDivider()

        Text("Paired devices", style = MaterialTheme.typography.titleSmall)
        if (c.pairedDevices.isEmpty()) {
            Text("You haven't paired with any devices yet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        } else {
            c.pairedDevices.forEach { device ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrustBadge(true)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(device.recipientHandle, style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
                        }
                        TextButton(onClick = { c.unpair(device.recipientHandle) }) {
                            Text("Unpair", color = Color(0xFFB3261E))
                        }
                    }
                }
            }
        }

        SectionDivider()

        Text("Identity backup", style = MaterialTheme.typography.titleSmall)
        Text(
            "Save your keypair and paired devices as a passphrase-protected file to move them to a new " +
                "computer. Importing replaces this computer's identity with the backup's.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
        if (c.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !c.busy, onClick = { pickSaveFile("relaypony-identity.age")?.let { exportDest = it } }) {
                Text("Export identity…")
            }
            OutlinedButton(enabled = !c.busy, onClick = { pickOpenFile("Import identity")?.let { importFile = it } }) {
                Text("Import identity…")
            }
        }
    }

    exportDest?.let { dest ->
        PassphraseDialog(
            title = "Export identity",
            help = "Protect the backup with a passphrase — you'll need it to import on the other device. There's no recovery if you forget it.",
            confirmLabel = "Export",
            requireConfirmation = true,
            onDismiss = { exportDest = null },
            onConfirm = { pass -> exportDest = null; c.exportIdentity(pass, dest) },
        )
    }
    importFile?.let { file ->
        PassphraseDialog(
            title = "Import identity",
            help = "Enter the passphrase for \"${file.name}\". This replaces this computer's identity and merges in the backup's paired devices.",
            confirmLabel = "Import",
            requireConfirmation = false,
            onDismiss = { importFile = null },
            onConfirm = { pass -> importFile = null; c.importIdentity(pass, file) },
        )
    }
}

@Composable
private fun PassphraseDialog(
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
                    label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation(),
                )
                if (requireConfirmation) {
                    OutlinedTextField(
                        value = confirm, onValueChange = { confirm = it }, singleLine = true,
                        label = { Text("Confirm passphrase") }, visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (mismatch) Text("Passphrases don't match.", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB3261E))
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onConfirm(pass) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22000000)))
}

// --- desktop glue ---

private fun qrImageBitmap(text: String, scale: Int = 6): ImageBitmap {
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

private fun pickFiles(): List<File> {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose files to send", java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files?.toList() ?: emptyList()
}

private fun pickSaveFile(defaultName: String): File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, "Export identity", java.awt.FileDialog.SAVE)
    d.file = defaultName
    d.isVisible = true
    val dir = d.directory
    val name = d.file
    return if (dir != null && name != null) File(dir, name) else null
}

private fun pickOpenFile(title: String): File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    d.isVisible = true
    val dir = d.directory
    val name = d.file
    return if (dir != null && name != null) File(dir, name) else null
}

private fun openFile(file: File) {
    runCatching { java.awt.Desktop.getDesktop().open(file) }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
