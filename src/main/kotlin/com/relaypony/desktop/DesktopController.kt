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

    /** "192.168.1.24:45789  (wlan0)" per interface — so a stuck user always has an address to type. */
    val reachableAt = mutableStateListOf<String>()
    /** Interfaces the advertisement actually went out on. Empty while receiving = undiscoverable. */
    val advertisingOn = mutableStateListOf<String>()
    /** Anything mDNS could not do, in plain words, rather than swallowed by a runCatching. */
    val networkProblems = mutableStateListOf<String>()

    /** mDNS + UDP beacon together; see [PeerFinder] for why one mechanism is not enough. */
    private val finder = PeerFinder(myHandle)
    private var server: ServerSocket? = null

    /** Addresses that answered the last subnet scan but that discovery never reported. */
    val sweepHits = mutableStateListOf<SubnetSweep.Hit>()
    var scanning by mutableStateOf(false); private set

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
            finder.start { p ->
                ui {
                    val i = peers.indexOfFirst { it.recipientHandle == p.recipientHandle }
                    if (i >= 0) peers[i] = p else peers.add(p)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * The "look harder" button: an active beacon probe, then a TCP scan of the local subnet for
     * anything discovery missed. The scan can't identify who answered — see [SubnetSweep] — so its
     * results are offered as addresses to send to, not as discovered devices.
     */
    fun rescan() {
        if (scanning) return
        scanning = true
        status = "Searching…"
        Thread {
            runCatching {
                finder.findOnce(2500).forEach { p ->
                    ui {
                        val i = peers.indexOfFirst { it.recipientHandle == p.recipientHandle }
                        if (i >= 0) peers[i] = p else peers.add(p)
                    }
                }
                val known = peers.toList()
                val port = if (listenPort > 0) listenPort else Config.listenPort
                val hits = finder.sweep(port, known)
                ui {
                    sweepHits.clear(); sweepHits.addAll(hits)
                    status = when {
                        peers.isNotEmpty() && hits.isEmpty() -> "Found ${peers.size} device(s)."
                        hits.isEmpty() -> "Nothing found. Check both devices are on the same network."
                        else -> "Found ${peers.size} device(s) and ${hits.size} unidentified address(es)."
                    }
                }
            }.onFailure { e -> ui { status = "Search failed: ${e.message ?: e.javaClass.simpleName}" } }
            ui { scanning = false }
        }.apply { isDaemon = true; start() }
    }

    // --- Wi-Fi Direct -------------------------------------------------------------------------

    /** Nearby Wi-Fi Direct devices. Populated only while a P2P search is running. */
    val p2pPeers = mutableStateListOf<WifiDirectLinux.P2pPeer>()
    var p2pReadiness by mutableStateOf<WifiDirectLinux.Readiness?>(null); private set
    var p2pBusy by mutableStateOf(false); private set
    var p2pStatus by mutableStateOf(""); private set
    val p2pTranscript = mutableStateListOf<String>()
    private var p2p: WifiDirectLinux? = null

    /** Ask whether Wi-Fi Direct can work here at all, before offering the user any of it. */
    fun p2pCheck() {
        if (p2pBusy) return
        p2pBusy = true
        p2pStatus = "Checking Wi-Fi Direct support…"
        Thread {
            val radio = WifiDirectLinux()
            val readiness = runCatching { radio.preflight() }.getOrElse {
                WifiDirectLinux.Readiness(false, listOf(it.message ?: "check failed"), emptyList())
            }
            radio.close()
            ui {
                p2pReadiness = readiness
                p2pBusy = false
                p2pStatus = if (readiness.usable) "Wi-Fi Direct is available." else "Wi-Fi Direct is unavailable here."
            }
        }.apply { isDaemon = true; start() }
    }

    fun p2pFind() {
        if (p2pBusy) return
        p2pBusy = true
        p2pPeers.clear()
        p2pStatus = "Looking for nearby devices…"
        Thread {
            val radio = p2p ?: WifiDirectLinux().also { p2p = it }
            runCatching {
                radio.startDiscovery { peer -> ui { if (p2pPeers.none { it.deviceAddress == peer.deviceAddress }) p2pPeers.add(peer) } }
                Thread.sleep(20_000)
                radio.knownPeers().forEach { peer ->
                    ui { if (p2pPeers.none { it.deviceAddress == peer.deviceAddress }) p2pPeers.add(peer) }
                }
                radio.stopDiscovery()
            }.onFailure { e -> ui { p2pStatus = "Search failed: ${e.message}" } }
            ui {
                p2pBusy = false
                p2pTranscript.clear(); p2pTranscript.addAll(radio.transcript())
                if (p2pStatus.startsWith("Looking")) {
                    p2pStatus = if (p2pPeers.isEmpty()) "No Wi-Fi Direct devices found." else "Found ${p2pPeers.size}."
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * Form a group with [peer] and run one transfer. Empty [files] means "wait to receive" — the
     * [com.relaypony.session.WifiIdent] handshake refuses if both sides want the same direction.
     */
    fun p2pTransfer(peer: WifiDirectLinux.P2pPeer, files: List<File>) {
        if (p2pBusy) return
        p2pBusy = true
        p2pStatus = "Connecting to ${peer.name} — accept the invitation on the phone…"
        Thread {
            val radio = p2p ?: WifiDirectLinux().also { p2p = it }
            runCatching {
                val group = radio.connect(peer.deviceAddress)
                ui { p2pStatus = "Group up on ${group.iface}. Setting up addressing…" }
                val peerIp = radio.resolvePeerAddress(group)
                val outcome = WifiDirectSession.run(
                    isGroupOwner = group.isGroupOwner,
                    peerAddress = peerIp,
                    provider = provider,
                    identity = identity,
                    deviceName = deviceName,
                    myHandle = myHandle,
                    trust = trust,
                    filesToSend = files,
                    inbox = inbox,
                    onStatus = { s -> ui { p2pStatus = s } },
                    onProgress = { sent, total ->
                        ui { sendProgress = if (total > 0L) (sent.toFloat() / total).coerceIn(0f, 1f) else null }
                    },
                )
                ui {
                    sendProgress = null
                    p2pStatus = when (outcome) {
                        is WifiDirectSession.Outcome.Sent -> "Sent ${outcome.files} file(s) to ${outcome.to}."
                        is WifiDirectSession.Outcome.Received -> {
                            outcome.files.forEach { received.add(0, it) }
                            "Received ${outcome.files.size} file(s) from ${outcome.from}."
                        }
                    }
                }
            }.onFailure { e ->
                ui { sendProgress = null; p2pStatus = "Wi-Fi Direct failed: ${e.message ?: e.javaClass.simpleName}" }
            }
            runCatching { radio.disconnect() }
            ui { p2pBusy = false; p2pTranscript.clear(); p2pTranscript.addAll(radio.transcript()) }
        }.apply { isDaemon = true; start() }
    }

    fun p2pStop() {
        Thread { runCatching { p2p?.close() }; p2p = null }.apply { isDaemon = true; start() }
        p2pBusy = false
        p2pStatus = "Wi-Fi Direct stopped."
    }

    // --- Pairing and trust --------------------------------------------------------------------

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
            runCatching { finder.stopAdvertising() }
            advertiseNow()
        }.apply { isDaemon = true; start() }
    }

    /**
     * Announce ourselves and report honestly what happened. The old version wrapped this in a bare
     * runCatching, so a total failure to advertise looked identical to success: the user saw
     * "Listening on port N" while being invisible to every phone on the network.
     */
    private fun advertiseNow() {
        val port = listenPort
        if (port <= 0) return
        val ok = runCatching { finder.advertise(port, deviceName, myHandle) }.getOrDefault(emptyList())
        val diag = runCatching { finder.diagnostics() }.getOrNull()
        ui {
            advertisingOn.clear(); advertisingOn.addAll(ok)
            networkProblems.clear(); diag?.problems?.let { networkProblems.addAll(it) }
            reachableAt.clear(); reachableAt.addAll(LocalNet.reachableAt(port))
            status = if (ok.isEmpty()) {
                "mDNS could not advertise — the beacon is still running, so newer devices can " +
                    "still find you at ${reachableAt.firstOrNull() ?: "this machine's address"}."
            } else {
                "Discoverable on ${ok.joinToString(", ")} + beacon · port $port"
            }
        }
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
                ui {
                    status = "Send to ${peer.name} failed: ${e.message ?: e.javaClass.simpleName} " +
                        "(tried ${peer.host}:${peer.port})"
                    sendProgress = null
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun startReceiving() {
        if (receiving) return
        // Prefer the stable default port so the address is predictable and firewall-ruleable;
        // fall back to an OS-assigned one rather than refusing to start if it's taken.
        val srv = LocalNet.listen(Config.listenPort)
        server = srv
        receiving = true
        listenPort = srv.localPort
        reachableAt.clear(); reachableAt.addAll(LocalNet.reachableAt(srv.localPort))
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
        finder.stopAdvertising()
        receiving = false
        listenPort = 0
        advertisingOn.clear()
        reachableAt.clear()
        status = "Stopped receiving"
    }

    /**
     * Send to a paired device at an address the user typed, with no discovery involved.
     *
     * This is the way out of every network where multicast doesn't survive — a phone hotspot, guest
     * Wi-Fi with client isolation, a VPN. The peer's key comes from the pairing, which is the part
     * an IP address can't supply; wire v1 is the frozen floor every build understands, since we
     * can't ask an undiscovered peer what it speaks.
     */
    fun sendToAddress(files: List<File>, device: PinnedDevice, host: String, port: Int) {
        val peer = DesktopDiscovery.Peer(
            name = device.name,
            host = host.trim(),
            port = port,
            recipientHandle = device.recipientHandle,
            maxWire = 1,
        )
        send(files, peer)
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
