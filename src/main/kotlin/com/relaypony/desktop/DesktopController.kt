package com.relaypony.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.relaypony.crypto.AgeProvider
import com.relaypony.session.FileNames
import com.relaypony.session.IdentityBackup
import com.relaypony.session.OutgoingFile
import com.relaypony.session.SocketTransfer
import com.relaypony.session.pairing.Pairing
import com.relaypony.session.pairing.PinnedDevice
import com.relaypony.session.pairing.QrPayload
import com.relaypony.session.pairing.Sas
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import javax.swing.SwingUtilities

/**
 * The GUI's application state and operations, built on the same reused core the CLI uses
 * (DesktopDiscovery, FileTrustStore, FileIdentityStore, SocketTransfer). Discovery, receive, and
 * send run on background threads; all Compose state is mutated on the Swing/AWT event thread via
 * [ui], which is where Compose Desktop runs its composition.
 */
class DesktopController {

    private val provider = AgeProvider()
    private val identityStore = FileIdentityStore(Config.identityFile)
    private var identity = identityStore.loadOrCreate(provider)   // reassigned on identity import
    private val trust = FileTrustStore(Config.trustFile)
    private val inbox = Config.defaultInbox()

    /** Editable, persisted display name. Changing it re-encodes the QR and re-advertises. */
    var deviceName by mutableStateOf(Config.loadName()); private set
    /** Our age handle (public key). Stable across runs; only an identity import changes it. */
    var myHandle by mutableStateOf(computeHandle()); private set
    var qrPayload by mutableStateOf(buildQr()); private set

    val peers = mutableStateListOf<DesktopDiscovery.Peer>()
    val received = mutableStateListOf<File>()
    val pairedHandles = mutableStateListOf<String>()          // drives recomposition when trust changes
    val pairedDevices = mutableStateListOf<PinnedDevice>()     // full records for the Settings list
    var receiving by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var listenPort by mutableStateOf(0); private set
    var sendProgress by mutableStateOf<Float?>(null); private set   // null = no send in flight
    var busy by mutableStateOf(false); private set                 // an export/import is running

    private val discovery = DesktopDiscovery()   // one jmdns instance: browses AND advertises
    private var server: ServerSocket? = null

    init {
        refreshPaired()
        (inbox.listFiles()?.filter { it.isFile } ?: emptyList())
            .sortedByDescending { it.lastModified() }
            .forEach { received.add(it) }
    }

    private fun computeHandle(): String =
        String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)

    private fun buildQr(): String =
        QrPayload(QrPayload.CURRENT_VERSION, provider.schemeId, myHandle, deviceName).encode()

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    private fun refreshPaired() {
        val all = trust.all()
        pairedHandles.clear(); pairedHandles.addAll(all.map { it.recipientHandle })
        pairedDevices.clear(); pairedDevices.addAll(all)
    }

    /** Start continuous discovery for the Send tab. Call once. */
    fun startBrowsing() {
        Thread {
            discovery.browse { p ->
                ui {
                    val i = peers.indexOfFirst { it.recipientHandle == p.recipientHandle }
                    if (i >= 0) peers[i] = p else peers.add(p)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun isPaired(handle: String): Boolean = Pairing.canSendOneTap(handle, trust)
    fun sasFor(peer: DesktopDiscovery.Peer): String = Sas.code(myHandle, peer.recipientHandle)

    fun pair(peer: DesktopDiscovery.Peer) {
        trust.pin(peer.recipientHandle, peer.name)
        ui {
            refreshPaired()
            status = "Paired with ${peer.name}"
        }
    }

    /** Remove a pairing. Trust is keyed on the handle, so that is what we drop. */
    fun unpair(handle: String) {
        val name = trust.get(handle)?.name ?: "device"
        trust.remove(handle)
        refreshPaired()
        status = "Unpaired $name"
    }

    /** Rename this device: persist, re-encode the QR, and (if live) re-announce over mDNS. */
    fun rename(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean == deviceName) return
        Config.saveName(clean)
        deviceName = clean
        qrPayload = buildQr()
        status = "Device name is now \"$clean\""
        if (receiving) reAdvertise()
    }

    /** Write a passphrase-protected backup of this identity + paired devices to [dest]. */
    fun exportIdentity(passphrase: String, dest: File) {
        ui { busy = true; status = "Exporting identity…" }
        Thread {
            val result = runCatching {
                dest.outputStream().use { out ->
                    IdentityBackup.export(passphrase, provider.identityToString(identity), trust.all(), out)
                }
                runCatching { Files.setPosixFilePermissions(dest.toPath(), setOf(OWNER_READ, OWNER_WRITE)) }
            }
            ui {
                busy = false
                result.onSuccess { status = "Exported identity to ${dest.name}" }
                    .onFailure { status = "Export failed: ${it.message ?: it.javaClass.simpleName}" }
            }
        }.apply { isDaemon = true; start() }
    }

    /** Restore an identity backup: adopt its keypair (new handle) and merge its paired devices. */
    fun importIdentity(passphrase: String, src: File) {
        ui { busy = true; status = "Importing identity…" }
        Thread {
            val result = runCatching { src.inputStream().use { IdentityBackup.import(passphrase, it) } }
            ui {
                busy = false
                result.onSuccess { imported ->
                    identityStore.save(imported.identitySecret)
                    identity = provider.identityFromString(imported.identitySecret)
                    imported.devices.forEach { trust.pin(it.recipientHandle, it.name, it.pinnedAtEpochMs) }
                    myHandle = computeHandle()
                    qrPayload = buildQr()
                    refreshPaired()
                    status = "Imported identity · ${imported.devices.size} paired device(s)"
                    if (receiving) reAdvertise()
                }.onFailure { status = "Import failed: ${it.message ?: it.javaClass.simpleName}" }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun reAdvertise() {
        Thread {
            runCatching { discovery.stopAdvertising() }
            advertiseNow()
        }.apply { isDaemon = true; start() }
    }

    private fun advertiseNow() {
        val port = listenPort
        if (port > 0) runCatching { discovery.advertise("RelayPony-$port", port, deviceName, myHandle) }
    }

    fun send(files: List<File>, peer: DesktopDiscovery.Peer) {
        ui { status = "Sending ${files.size} file(s) to ${peer.name}…"; sendProgress = 0f }
        Thread {
            try {
                val recipient = provider.recipientFromQr(peer.recipientHandle.toByteArray(Charsets.UTF_8))
                val outgoing = files.map { f ->
                    OutgoingFile(f.name, "application/octet-stream", f.length()) { FileInputStream(f) }
                }
                SocketTransfer.sendTo(
                    peer.host, peer.port, provider, listOf(recipient), deviceName, myHandle, outgoing,
                    peerMaxWire = peer.maxWire,
                    onProgress = { sent, total ->
                        ui { sendProgress = if (total > 0L) (sent.toFloat() / total).coerceIn(0f, 1f) else null }
                    },
                )
                ui { status = "Sent ${files.size} file(s) to ${peer.name}."; sendProgress = null }
            } catch (e: Exception) {
                ui { status = "Send failed: ${e.message}"; sendProgress = null }
            }
        }.apply { isDaemon = true; start() }
    }

    fun startReceiving() {
        if (receiving) return
        val srv = ServerSocket(0)
        server = srv
        receiving = true
        listenPort = srv.localPort
        status = "Listening on port ${srv.localPort}"
        Thread {
            advertiseNow()
            while (!srv.isClosed) {
                val written = LinkedHashMap<String, File>()
                try {
                    SocketTransfer.receiveOnceFrom(
                        srv, provider, identity, deviceName = deviceName, recipientHandle = myHandle,
                    ) { entry ->
                        val dest = uniqueFile(inbox, FileNames.sanitize(entry.name))
                        written[entry.name] = dest
                        dest.outputStream()
                    }
                    ui {
                        written.values.filter { it.exists() }.forEach { received.add(0, it) }
                        status = "Received ${written.size} file(s)"
                    }
                } catch (e: Exception) {
                    if (srv.isClosed) break
                    ui { status = "Receive error: ${e.message}" }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopReceiving() {
        server?.let { runCatching { it.close() } }
        server = null
        discovery.stopAdvertising()
        receiving = false
        listenPort = 0
        status = "Stopped receiving"
    }

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }
}
