package com.relaypony.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.relaypony.crypto.AgeProvider
import com.relaypony.pake.WordCodePairing
import com.relaypony.session.FileNames
import com.relaypony.session.FolderZip
import com.relaypony.session.HelloAuth
import com.relaypony.session.IdentityBackup
import com.relaypony.session.OutgoingFile
import com.relaypony.session.RefusedException
import com.relaypony.session.SocketTransfer
import com.relaypony.session.TransferLimits
import com.relaypony.session.UnsafeArchiveException
import com.relaypony.session.ZipRefusal
import com.relaypony.session.pairing.LanPair
import com.relaypony.session.pairing.PairingService
import com.relaypony.session.pairing.PakeDetails
import com.relaypony.session.pairing.PeerRoute
import com.relaypony.session.pairing.PinnedDevice
import com.relaypony.session.pairing.QrPayload
import com.relaypony.session.pairing.Sas
import com.relaypony.session.wan.RelayClient
import com.relaypony.session.wan.RelayConfig
import com.relaypony.session.wan.RelayUrls
import com.relaypony.session.wan.WanStatus
import com.relaypony.session.wan.WanStatusKind
import com.relaypony.session.wan.WanTransfer
import com.relaypony.transport.WireProtocol
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/**
 * The GUI's application state and operations, on the Android app's protocol core (PROTOCOL_v3,
 * RelayPony 4.0): QR v2 pairing, paired-only receive with a signed HELLO, sends over the LAN or the
 * internet, folders as safe zips and text drops. Mirrors the Android TransferController so the two
 * behave the same. Network work runs on background threads; Compose state is only touched on the
 * Swing event thread, through [ui] or the WAN layer's [main].
 */
class DesktopController {

    private val main = SwingMainThread()
    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    private val provider = AgeProvider()
    private val identityStore = FileIdentityStore(Config.identityFile)
    private val identity = identityStore.loadOrCreate(provider)
    private val trust = FileTrustStore(Config.trustFile)
    private val routes = FilePeerRouteStore()
    private val settings = DesktopSettings()
    private val inbox = Config.defaultInbox()

    /** RELAYPONY_DEBUG=1: a timestamped trace of internet transfers on stderr, for tracking a failure. */
    private val debugOn = System.getenv("RELAYPONY_DEBUG") == "1"

    /** This device's age handle (public key). An identity import takes effect at the next launch. */
    val myHandle: String = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
    private val myScalar: ByteArray = provider.scalarOf(identity)

    /** Editable, persisted display name. Changing it refreshes the QR and re-advertises. */
    var deviceName by mutableStateOf(Config.loadName()); private set

    val peers = mutableStateListOf<DesktopDiscovery.Peer>()
    /** A received file, who it came from (the name this computer paired with), and when. */
    data class ReceivedItem(val file: File, val from: String, val atMs: Long)

    /** Received files, newest first. */
    val received = mutableStateListOf<ReceivedItem>()
    private val receivedLog = ReceivedLog(File(Config.configDir, "received.json"))
    val pairedHandles = mutableStateListOf<String>()          // drives recomposition when trust changes
    val pairedDevices = mutableStateListOf<PinnedDevice>()     // full records for the lists
    var receiving by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var listenPort by mutableStateOf(0); private set
    var receiveProgress by mutableStateOf<Float?>(null); private set
    var sendProgress by mutableStateOf<Float?>(null); private set   // Wi-Fi Direct only
    var busy by mutableStateOf(false); private set                 // an export/import is running
    /** Something the user should read now (a folder problem, an extract result). Shown briefly. */
    var notice by mutableStateOf<String?>(null)

    /** A failure: a plain reason, plus the raw detail behind a Details toggle for bug reports. */
    data class Problem(val text: String, val detail: String?)

    var problem by mutableStateOf<Problem?>(null)

    private fun fail(textKey: String, t: Throwable, vararg args: Any?) {
        debug("$textKey: $t")
        problem = Problem(I18n.t(textKey, *args), t.message ?: t.javaClass.name)
    }

    /** "192.168.1.24:45789  (wlan0)" per interface, so a stuck user always has an address to type. */
    val reachableAt = mutableStateListOf<String>()
    /** Interfaces the advertisement actually went out on. Empty while receiving = undiscoverable. */
    val advertisingOn = mutableStateListOf<String>()
    /** Anything mDNS could not do, in plain words. */
    val networkProblems = mutableStateListOf<String>()

    /** mDNS + UDP beacon together; see [PeerFinder] for why one mechanism is not enough. */
    private val finder = PeerFinder(myHandle)
    @Volatile private var server: ServerSocket? = null
    /** Whether the user wants to receive. Pairing can hold the listener up while this is off. */
    private var wantsReceiving = true

    /** Addresses that answered the last subnet scan but that discovery never reported. */
    val sweepHits = mutableStateListOf<SubnetSweep.Hit>()
    var scanning by mutableStateOf(false); private set

    // ---- Relay and private inbox (PROTOCOL_v3 sections 5 and 8) ----

    /** The relay URL as the user typed it, empty for the default. */
    var relayServer by mutableStateOf(settings.relayServer); private set

    /** This device's relay, normalized ("" for the default). */
    private val myRelay: String get() = RelayUrls.normalize(relayServer)

    /** Whether this device's relay has mailboxes (relay 2.0). Null until the first check answers. */
    var relayHasMailboxes by mutableStateOf<Boolean?>(null); private set

    private val relayClients = ConcurrentHashMap<String, RelayClient>()
    private fun relayClient(normalized: String): RelayClient =
        relayClients.getOrPut(normalized) { RelayClient(RelayUrls.base(normalized)) }

    private fun checkRelayFeatures() {
        val relay = myRelay
        background("relaypony-relay-info") {
            val info = relayClient(relay).info()
            ui { if (relay == myRelay) relayHasMailboxes = info?.hasMailboxes ?: false }
        }
    }

    /** Point this device at another relay (self-hosting), or back to the default with "". */
    fun setRelay(url: String) {
        val v = url.trim()
        settings.relayServer = v
        relayServer = v
        RelayConfig.baseUrl = RelayUrls.base(RelayUrls.normalize(v))
        // This device's inbox now lives on another relay: check what it supports, and tell paired
        // devices again on next contact (PROTOCOL_v3 section 5.3).
        relayClients.clear()
        relayHasMailboxes = null
        settings.clearInboxUsers()
        checkRelayFeatures()
        notice = if (v.isEmpty()) I18n.t("set_relay_reset_done") else I18n.t("set_relay_saved")
    }

    /** Give this device a new inbox. Paired devices learn it on next contact. */
    fun rotateInbox() {
        settings.rotateInbox()
        notice = I18n.t("d_rotate_done")
    }

    // ---- Paired-only receive (PROTOCOL_v3 section 9) ----

    /** Take LAN transfers from devices that aren't paired. Off unless the user turns it on. */
    var acceptUnpaired by mutableStateOf(settings.acceptUnpaired); private set

    fun setAcceptUnpairedSetting(on: Boolean) {
        acceptUnpaired = on
        settings.acceptUnpaired = on
    }

    /** Tags already accepted, so a captured HELLO can't be used twice. Hex tag to time seen. */
    private val seenTags = HashMap<String, Long>()

    private fun firstUseOf(tag: ByteArray, nowMs: Long): Boolean = synchronized(seenTags) {
        seenTags.entries.removeAll { nowMs - it.value > TAG_MEMORY_MS }
        val key = tag.joinToString("") { "%02x".format(it) }
        if (seenTags.containsKey(key)) false else { seenTags[key] = nowMs; true }
    }

    /** Whether to take a LAN transfer from the sender of [hello]. Null accepts; a reason refuses. */
    private fun receiveGate(hello: WireProtocol.Hello): String? {
        val from = hello.recipientHandle
        val pinned = trust.isPinned(from)
        val auth = hello.auth
        val now = System.currentTimeMillis()
        if (pinned && auth != null) {
            if (HelloAuth.verify(myScalar, myHandle, from, auth, now) && firstUseOf(auth.tag, now)) {
                settings.markTaggedPeer(from)
                return null
            }
            return refuse(hello)
        }
        // A paired 3.x device sends no tag. Once a device has tagged, an untagged HELLO claiming
        // to be it is refused.
        if (pinned && !settings.isTaggedPeer(from)) return null
        if (!pinned && acceptUnpaired) return null
        return refuse(hello)
    }

    private fun refuse(hello: WireProtocol.Hello): String {
        val name = hello.deviceName.take(64)
        val handle = hello.recipientHandle
        ui { noteRequest(handle, name) }
        return "unpaired"
    }

    /** The label for received files: the name this device paired with, never the sender's own. */
    private fun senderLabel(handle: String, claimedName: String): String =
        trust.get(handle)?.name ?: I18n.t("rec_unpaired_label", claimedName)

    /** A device that tried to send here and was refused. Name and handle are its own claim. */
    data class SendRequest(val handle: String, val name: String, val atMs: Long)

    /** Recent refused senders, newest first. */
    val requests = mutableStateListOf<SendRequest>()

    private fun noteRequest(handle: String, name: String) {
        requests.removeAll { it.handle == handle }
        requests.add(0, SendRequest(handle, name, System.currentTimeMillis()))
        while (requests.size > MAX_REQUESTS) requests.removeAt(requests.lastIndex)
        status = I18n.t("req_title", name)
    }

    fun dismissRequest(request: SendRequest) { requests.remove(request) }

    // ---- WAN transfer (PonyDirect with the relay as fallback) ----

    /** What the internet receiver is doing, for the Receive screen. */
    var wanReceiveStatus by mutableStateOf(""); private set

    private val wan = WanTransfer(
        provider = provider,
        identity = identity,
        myScalar = myScalar,
        myHandle = myHandle,
        deviceName = deviceName,
        saveDir = { inbox },
        isPinned = { trust.isPinned(it) },
        // Inbound WAN transfers spool here (ciphertext) before decoding, not in Downloads.
        spoolDir = { File(Config.configDir, "wan-spool") },
        limits = { receiveLimits() },
        isSealedPeer = { settings.isSealedPeer(it) },
        markSealedPeer = { settings.markSealedPeer(it) },
        main = main,
    ).apply {
        onSendStatus = { peer, st -> debug("send ${peer.take(16)}: ${st.kind}${st.arg?.let { " ($it)" } ?: ""}"); onWanLegStatus(peer, st) }
        onSendProgress = { peer, sent, total ->
            debug("send ${peer.take(16)}: $sent / $total bytes")
            updateLeg(peer) { if (it.route != SendRoute.NEARBY && it.active) it.copy(progress = sent.toFloat() / total) else it }
        }
        onReceiveStatus = { st -> debug("receive: ${st.kind}${st.arg?.let { " ($it)" } ?: ""}"); wanReceiveStatus = wanStatusText(st) }
        onReceived = { batch -> recordWanReceived(batch) }
    }

    private fun wanStatusText(st: WanStatus): String = when (st.kind) {
        WanStatusKind.READY -> I18n.t("wan_ready")
        WanStatusKind.IN_PROGRESS -> I18n.t("wan_in_progress")
        WanStatusKind.ADD_FILES -> I18n.t("wan_add_files")
        WanStatusKind.PREPARING -> I18n.t("wan_preparing")
        WanStatusKind.PREPARE_FAILED -> I18n.t("wan_prepare_failed", st.arg ?: I18n.t("wan_unknown_error"))
        WanStatusKind.CONNECTING -> I18n.t("wan_connecting")
        WanStatusKind.SENDING -> I18n.t("wan_sending")
        WanStatusKind.SENDING_RELAY -> I18n.t("wan_sending_relay")
        WanStatusKind.SENT -> I18n.t("wan_sent")
        WanStatusKind.SEND_FAILED -> I18n.t("wan_send_failed")
        WanStatusKind.RECEIVING -> I18n.t("wan_receiving")
        WanStatusKind.RECEIVING_RELAY -> I18n.t("wan_receiving_relay")
        WanStatusKind.RECEIVED -> I18n.t("wan_received", st.count, st.arg ?: "")
        WanStatusKind.RECEIVE_FAILED -> I18n.t("wan_receive_failed", st.arg ?: I18n.t("wan_unknown_error"))
        WanStatusKind.CANCELLED -> I18n.t("wan_cancelled")
    }

    private fun recordWanReceived(batch: WanTransfer.ReceivedBatch) {
        val label = senderLabel(batch.peerHandle, batch.senderName)
        recordReceived(batch.files.map { File(it.path) }, label)
    }

    // ---- Pairing (PROTOCOL_v3 sections 3 and 4) ----

    private val pairingService: PairingService = PairingService(
        provider = provider,
        identity = identity,
        myScalar = myScalar,
        myHandle = myHandle,
        myName = { deviceName },
        myInbox = { settings.inboxId },
        myRelay = { myRelay },
        trust = trust,
        routes = routes,
        mailer = { peer, relay, inboxId, payload ->
            // Both paths, always: the relay reaches a peer anywhere, the LAN reaches one with no
            // internet at all. The receiver handles whichever copy lands first.
            background("relaypony-pair-send") { runCatching { relayClient(relay).mboxSend(inboxId, payload) } }
            deliverPairOverLan(peer, payload)
        },
    )

    val pairing = DesktopPairing(
        service = pairingService,
        myHandle = myHandle,
        main = main,
        setListening = { on -> setPairListening(on) },
        onPinned = { handle, name ->
            requests.removeAll { it.handle == handle }
            refreshPaired()
            status = I18n.t("st_paired_with", name)
        },
        onUnpinned = { handle -> afterUnpin(handle) },
        newWordCode = { WordCodePairing(PakeDetails(myHandle, deviceName, settings.inboxId, myRelay), { relay -> relayClient(relay) }) },
        relayHasMailboxes = { relayHasMailboxes },
        pinWordCodePeer = { peer ->
            trust.pin(peer.handle, peer.name)
            routes.put(peer.handle, PeerRoute(peer.inboxId, peer.relay))
        },
    )

    init {
        if (relayServer.isNotEmpty()) RelayConfig.baseUrl = RelayUrls.base(myRelay)
        pairingService.onEvent = { ev -> pairing.onEvent(ev) }
        wan.hooks = object : WanTransfer.InboxHooks {
            override fun myInbox(): String? = if (relayHasMailboxes == true) settings.inboxId else null
            override fun myRelayClient(): RelayClient = relayClient(myRelay)
            override fun route(peer: String): PeerRoute? = routes.get(peer)
            override fun clientFor(relay: String): RelayClient = relayClient(relay)
            override fun announcement(peer: String): ByteArray? =
                if (relayHasMailboxes == true && trust.isPinned(peer)) pairingService.inboxAnnouncement(peer) else null
            override fun peerUsesMyInbox(peer: String): Boolean = settings.peerUsesMyInbox(peer)
            override fun markPeerUsesMyInbox(peer: String) { debug("${peer.take(16)} reached this computer through its inbox"); settings.markPeerUsesMyInbox(peer) }
        }
        wan.onInboxMessage = { plain -> debug("inbox: ${plain.substringBefore('|')}"); pairingService.onSealedPlain(plain) }
        debug("my handle $myHandle, inbox ${settings.inboxId.take(8)}…, relay ${RelayUrls.base(myRelay)}")
        trust.all().forEach { d ->
            debug("paired ${d.name}: ${d.recipientHandle.take(16)}… route ${routes.get(d.recipientHandle)?.let { "inbox ${it.inboxId.take(8)}… on ${RelayUrls.base(it.relay)}" } ?: "none"}")
        }
        refreshPaired()
        val known = receivedLog.all()
        (inbox.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList())
            .map { f -> known[f.path]?.let { ReceivedItem(f, it.from, it.atMs) } ?: ReceivedItem(f, "", f.lastModified()) }
            .sortedByDescending { it.atMs }
            .forEach { received.add(it) }
        receivedLog.retain(received.map { it.file.path }.toSet())
        checkRelayFeatures()
    }

    /** Whether pairing turned the listener on, so it can turn it off again afterwards. */
    private var pairStartedListener = false

    /** Pairing needs the LAN listener and inbox polling even if the user paused receiving. */
    private fun setPairListening(on: Boolean) {
        wan.holdPolling(on)
        if (on) {
            if (server == null) {
                startListener()
                pairStartedListener = true
            }
        } else if (pairStartedListener) {
            pairStartedListener = false
            if (!wantsReceiving) stopListener()
        }
    }

    /**
     * Send a sealed pairing message straight to [peer] over the LAN when it is discovered and
     * accepts PAIR frames. If it isn't in the list yet, probe once. Best effort: the relay copy
     * goes out regardless.
     */
    private fun deliverPairOverLan(peer: String, payload: ByteArray) {
        val known = peers.toList()
        background("relaypony-pair-lan") {
            var target = known.firstOrNull { it.recipientHandle == peer && it.pairCapable }
            if (target == null) {
                runCatching {
                    finder.probe(1500) { p ->
                        addPeer(p)
                        if (target == null && p.recipientHandle == peer && p.pairCapable) target = p
                    }
                }
            }
            target?.let { t -> runCatching { LanPair.send(t.host, t.port, payload) } }
        }
    }

    fun isPaired(handle: String): Boolean = trust.isPinned(handle)

    /** The 3.x pairing QR (one-way pin), for a device that hasn't updated to RelayPony 4. */
    fun legacyQrText(): String = QrPayload(QrPayload.CURRENT_VERSION, provider.schemeId, myHandle, deviceName).encode()

    private fun refreshPaired() {
        // A 2.x build could pin this computer's own handle (mDNS lists ourselves); never offer it.
        val all = trust.all().filter { it.recipientHandle != myHandle }
        pairedHandles.clear(); pairedHandles.addAll(all.map { it.recipientHandle })
        pairedDevices.clear(); pairedDevices.addAll(all)
    }

    /**
     * Unpair [handle] (PROTOCOL_v3 section 4.7). The signed notice goes out best effort by every
     * path: the peer's inbox, its handle queue on this device's relay, and the LAN. Then the pin
     * and route are dropped here.
     */
    fun unpair(handle: String) {
        val name = trust.get(handle)?.name ?: "device"
        val unpairMsg = runCatching { pairingService.unpairNotice(handle) }.getOrNull()
        val route = routes.get(handle)
        pairingService.forget(handle)
        if (unpairMsg != null) {
            if (route != null) {
                background("relaypony-unpair") { runCatching { relayClient(route.relay).mboxSend(route.inboxId, unpairMsg) } }
            }
            // The route is gone now, so this one goes to the handle queue.
            wan.sendSealed(handle, unpairMsg)
            deliverPairOverLan(handle, unpairMsg)
        }
        afterUnpin(handle)
        notice = I18n.t("st_removed", name)
    }

    /** Clean-up shared by unpairing here and being unpaired by the other device. */
    private fun afterUnpin(handle: String) {
        settings.forgetInboxUser(handle)
        refreshPaired()
    }

    /** Rename this device: persist, refresh the QR, and re-announce if live. */
    fun rename(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean == deviceName) return
        Config.saveName(clean)
        deviceName = clean
        pairing.nameChanged()
        notice = I18n.t("set_name", clean)
        if (server != null) reAdvertise()
    }

    // ---- Identity backup ----

    /** Write a passphrase-protected backup of this identity, its inbox and paired devices to [dest]. */
    fun exportIdentity(passphrase: String, dest: File) {
        busy = true
        status = I18n.t("d_exporting")
        background("relaypony-export") {
            val result = runCatching {
                dest.outputStream().use { out ->
                    IdentityBackup.export(
                        passphrase, provider.identityToString(identity), trust.all(), out,
                        inboxId = settings.inboxId, routes = routes.all(),
                    )
                }
                runCatching { Files.setPosixFilePermissions(dest.toPath(), setOf(OWNER_READ, OWNER_WRITE)) }
            }
            ui {
                busy = false
                result.onSuccess { notice = I18n.t("d_exported", dest.name) }
                    .onFailure { fail("d_export_failed", it) }
            }
        }
    }

    /**
     * Restore an identity backup: save its keypair (used from the next launch, as on the phones)
     * and merge its paired devices, routes and inbox now.
     */
    fun importIdentity(passphrase: String, src: File) {
        busy = true
        status = I18n.t("d_importing")
        background("relaypony-import") {
            val result = runCatching { src.inputStream().use { IdentityBackup.import(passphrase, it) } }
            ui {
                busy = false
                result.onSuccess { imported ->
                    identityStore.save(imported.identitySecret)
                    imported.devices.forEach { trust.pin(it.recipientHandle, it.name, it.pinnedAtEpochMs) }
                    imported.routes.forEach { (handle, route) -> routes.put(handle, route) }
                    imported.inboxId?.let { settings.setInboxId(it) }
                    refreshPaired()
                    notice = I18n.t("d_imported_identity", imported.devices.size)
                }.onFailure { fail("d_import_failed", it) }
            }
        }
    }

    // ---- Discovery ----

    private fun addPeer(p: DesktopDiscovery.Peer) = ui {
        // mDNS hears this computer's own advertisement too.
        if (p.recipientHandle == myHandle) return@ui
        val i = peers.indexOfFirst { it.recipientHandle == p.recipientHandle }
        if (i >= 0) peers[i] = p else peers.add(p)
    }

    /** Start continuous discovery. Call once. */
    fun startBrowsing() {
        background("relaypony-browse") { finder.start { p -> addPeer(p) } }
    }

    /**
     * The "look harder" button: an active beacon probe, then a TCP scan of the local subnet for
     * anything discovery missed. The scan can't identify who answered (see [SubnetSweep]), so its
     * results are offered as addresses, not as discovered devices.
     */
    fun rescan() {
        if (scanning) return
        scanning = true
        status = I18n.t("st_discovering")
        background("relaypony-rescan") {
            runCatching {
                finder.findOnce(2500).forEach { p -> addPeer(p) }
                Thread.sleep(100)
                val known = peers.toList()
                val port = if (listenPort > 0) listenPort else Config.listenPort
                val hits = finder.sweep(port, known)
                ui {
                    sweepHits.clear(); sweepHits.addAll(hits)
                    notice = when {
                        peers.isNotEmpty() && hits.isEmpty() -> I18n.t("d_found_devices", peers.size)
                        hits.isEmpty() -> I18n.t("d_found_nothing")
                        else -> I18n.t("d_found_devices_and_addresses", peers.size, hits.size)
                    }
                }
            }.onFailure { e -> ui { fail("d_search_failed", e) } }
            ui { scanning = false }
        }
    }

    // ---- Receiving: always on while the app is open (plan section 9.1) ----

    /** Start everything at launch: discovery, the LAN listener and the internet receiver. */
    fun start() {
        startBrowsing()
        startReceiving()
    }

    private fun receiveLimits(): TransferLimits = TransferLimits(freeBytes = { inbox.usableSpace })

    /** The Receive switch, on. */
    fun startReceiving() {
        wantsReceiving = true
        pairStartedListener = false
        startListener()
        wan.startReceiving()
    }

    /** The Receive switch, off. In-flight transfers finish; new ones are not accepted. */
    fun stopReceiving() {
        wantsReceiving = false
        wan.stopReceiving()
        if (!pairStartedListener) stopListener()
        status = I18n.t("rec_paused_title")
    }

    private fun startListener() {
        if (server != null) { receiving = true; return }
        // Prefer the stable default port so the address is predictable and firewall-ruleable;
        // fall back to an OS-assigned one rather than refusing to start if it's taken.
        val srv = runCatching { LocalNet.listen(Config.listenPort) }.getOrElse {
            fail("d_no_port", it)
            return
        }
        server = srv
        receiving = true
        listenPort = srv.localPort
        reachableAt.clear(); reachableAt.addAll(LocalNet.reachableAt(srv.localPort))
        status = I18n.t("st_listening", srv.localPort, deviceName)
        background("relaypony-accept") {
            advertiseNow()
            // The listener survives individual failed transfers; only closing the socket ends it.
            while (!srv.isClosed) {
                val written = ArrayList<File>()
                try {
                    var lastPost = 0L
                    val result = SocketTransfer.acceptOne(
                        srv, provider, identity,
                        // A one-shot PAIR frame: hand it to pairing on the event thread, as the relay path does.
                        onPair = { sealed -> ui { pairingService.onLanPair(sealed) } },
                        deviceName = deviceName,
                        recipientHandle = myHandle,
                        onProgress = { recvd, total ->
                            val now = System.currentTimeMillis()
                            if (now - lastPost >= 150 || recvd >= total) {
                                lastPost = now
                                ui { receiveProgress = if (total > 0) (recvd.toFloat() / total).coerceIn(0f, 1f) else null }
                            }
                        },
                        limits = receiveLimits(),
                        gate = { hello -> receiveGate(hello) },
                    ) { entry ->
                        val dest = uniqueFile(inbox, FileNames.sanitize(entry.name))
                        written.add(dest)
                        dest.outputStream()
                    } ?: continue
                    val label = senderLabel(result.senderHandle, result.senderName)
                    ui {
                        receiveProgress = null
                        recordReceived(written, label)
                    }
                } catch (e: RefusedException) {
                    // Refused at HELLO: nothing was written, and the sender is listed under Requests.
                    continue
                } catch (e: Exception) {
                    // Drop any half-written files from the aborted transfer.
                    written.forEach { runCatching { it.delete() } }
                    ui { receiveProgress = null }
                    if (srv.isClosed) break
                    ui { status = I18n.t("st_receive_interrupted") }
                }
            }
            if (server === srv) server = null
        }
    }

    private fun stopListener() {
        server?.let { runCatching { it.close() } }
        server = null
        runCatching { finder.stopAdvertising() }
        receiving = false
        listenPort = 0
        advertisingOn.clear()
        reachableAt.clear()
    }

    private fun reAdvertise() {
        background("relaypony-advertise") {
            runCatching { finder.stopAdvertising() }
            advertiseNow()
        }
    }

    /** Announce ourselves and report honestly what happened. */
    private fun advertiseNow() {
        val port = listenPort
        if (port <= 0) return
        // pairCapable: this listener accepts PAIR frames, so peers may pair with us over the LAN.
        val ok = runCatching { finder.advertise(port, deviceName, myHandle, pairCapable = true) }.getOrDefault(emptyList())
        val diag = runCatching { finder.diagnostics() }.getOrNull()
        ui {
            advertisingOn.clear(); advertisingOn.addAll(ok)
            networkProblems.clear(); diag?.problems?.let { networkProblems.addAll(it) }
            reachableAt.clear(); reachableAt.addAll(LocalNet.reachableAt(port))
            status = if (ok.isEmpty()) {
                I18n.t("d_mdns_failed")
            } else {
                I18n.t("st_listening", port, deviceName)
            }
        }
    }

    // ---- What to send: files, a folder or text ----

    /** The staged content the next send carries. */
    val staged = mutableStateListOf<OutgoingFile>()

    fun stageFiles(files: List<File>) {
        val out = files.filter { it.isFile }.map { f ->
            OutgoingFile(f.name, guessMime(f.name), f.length()) { f.inputStream() }
        }
        if (out.isEmpty()) return
        setStaged(out)
    }

    /** Stage typed or pasted text as a ClipDrop .txt file. */
    fun stageText(text: String) {
        if (text.isNotBlank()) setStaged(listOf(ClipText.toOutgoing(text)))
    }

    fun clearStaged() {
        staged.clear()
        sweepFolderZips()
    }

    private fun setStaged(files: List<OutgoingFile>) {
        staged.clear()
        staged.addAll(files)
        sweepFolderZips()
        // Picked on Home: straight to choosing who gets it.
        if (files.isNotEmpty()) sendToOpen = true
    }

    /** A folder being zipped before it can be sent. */
    data class FolderPrep(val name: String, val done: Long, val total: Long)

    var folderPrep by mutableStateOf<FolderPrep?>(null); private set
    @Volatile private var folderCancelled = false
    private val folderZips = ConcurrentHashMap<OutgoingFile, File>()
    private fun folderZipDir(): File = File(Config.configDir, "folders")

    /** Zip [dir] in the background (PROTOCOL_v3 section 12.1), then stage it like any file. */
    fun stageFolder(dir: File) {
        if (folderPrep != null) return
        folderCancelled = false
        folderPrep = FolderPrep(dir.name, 0, 0)
        background("relaypony-folder") {
            var zip: File? = null
            try {
                val folder = PickedFolder.walk(dir, WireProtocol.MAX_MANIFEST_ENTRIES)
                if (folder.fileCount == 0) throw FolderProblem(I18n.t("folder_empty"))
                val total = folder.totalBytes
                if (total > TransferLimits.MAX_TRANSFER_BYTES || folder.items.any { it.size > TransferLimits.MAX_FILE_BYTES }) {
                    throw FolderProblem(I18n.t("folder_too_big"))
                }
                val zipDir = folderZipDir().apply { mkdirs() }
                if (total + TransferLimits.FREE_SPACE_MARGIN_BYTES > zipDir.usableSpace) {
                    throw FolderProblem(I18n.t("d_folder_no_space"))
                }
                ui { folderPrep = FolderPrep(folder.name, 0, total) }
                val name = FolderZip.archiveName(folder.name)
                val zipFile = File(zipDir, "${System.nanoTime()}-$name")
                zip = zipFile
                var done = 0L
                var lastPost = 0L
                zipFile.outputStream().buffered(256 * 1024).use { out ->
                    FolderZip.write(out, folder.name, folder.items, cancelled = { folderCancelled }) { n ->
                        done += n
                        if (done - lastPost >= (1L shl 20)) {
                            lastPost = done
                            val d = done
                            ui { folderPrep = folderPrep?.copy(done = d) }
                        }
                    }
                }
                val outgoing = OutgoingFile(name, "application/zip", zipFile.length()) { zipFile.inputStream() }
                folderZips[outgoing] = zipFile
                ui {
                    folderPrep = null
                    if (folderCancelled) { folderZips.remove(outgoing); zipFile.delete() } else setStaged(listOf(outgoing))
                }
            } catch (e: FolderZip.CancelledException) {
                zip?.delete()
                ui { folderPrep = null }
            } catch (e: FolderProblem) {
                zip?.delete()
                ui { folderPrep = null; notice = e.message }
            } catch (e: PickedFolder.TooManyItemsException) {
                ui { folderPrep = null; notice = I18n.t("folder_too_many", WireProtocol.MAX_MANIFEST_ENTRIES) }
            } catch (t: Throwable) {
                zip?.delete()
                ui { folderPrep = null; fail("folder_read_failed", t) }
            }
        }
    }

    fun cancelFolder() { folderCancelled = true }

    private class FolderProblem(message: String) : Exception(message)

    /** Delete folder zips that nothing staged or sending refers to any more. */
    private fun sweepFolderZips() {
        val inUse = (staged.toList() + batchFiles).toSet()
        folderZips.keys.filter { it !in inUse }.forEach { key -> folderZips.remove(key)?.delete() }
    }

    // ---- Sending (plan sections 4.4 and 8.1) ----

    /** The send the transfer screen shows, null when there is none. */
    var batch by mutableStateOf<OutgoingBatch?>(null); private set

    /** Whether the transfer screen is in front (it can be put away while a send runs). */
    var transferVisible by mutableStateOf(false)

    /** Whether the Send-to dialog is up, and which devices it starts with ticked. */
    var sendToOpen by mutableStateOf(false)
    val sendToPreselect = mutableStateListOf<String>()

    private var batchFiles: List<OutgoingFile> = emptyList()
    private val lanSockets = ConcurrentHashMap<String, Socket>()
    private val lanCancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True when [handle] is discovered on this network right now. */
    fun isNearby(handle: String): Boolean = peers.any { it.recipientHandle == handle }

    /**
     * Send the staged content to [handles], each by the route that reaches it: the LAN when it is
     * discovered here, the internet otherwise.
     */
    fun sendToDevices(handles: List<String>) {
        if (staged.isEmpty() || handles.isEmpty()) return
        if (batch?.active == true) {
            notice = I18n.t("xfer_busy")
            return
        }
        val files = staged.toList()
        val nearby = handles.associateWith { h -> peers.firstOrNull { it.recipientHandle == h } }
        val legs = handles.distinct().mapNotNull { h ->
            val device = trust.get(h) ?: return@mapNotNull null
            SendLeg(h, device.name, if (nearby[h] != null) SendRoute.NEARBY else SendRoute.INTERNET, LegState.CONNECTING)
        }
        if (legs.isEmpty()) return
        batchFiles = files
        batch = OutgoingBatch(System.currentTimeMillis(), files.map { it.name }, files.sumOf { it.size }, legs)
        transferVisible = true
        sendToOpen = false
        sendToPreselect.clear()
        legs.forEach { leg -> startLeg(leg.handle, nearby[leg.handle], files) }
    }

    /**
     * Send the staged content to a paired device at an address typed by hand. The way out of every
     * network where discovery doesn't get through; the key still comes from the pairing.
     */
    fun sendToAddress(device: PinnedDevice, host: String, port: Int) {
        if (staged.isEmpty()) return
        if (batch?.active == true) {
            notice = I18n.t("xfer_busy")
            return
        }
        val files = staged.toList()
        // We can't ask an undiscovered peer what it speaks, so use wire v1, the floor every build understands.
        val peer = DesktopDiscovery.Peer(device.name, host.trim(), port, device.recipientHandle, maxWire = 1, source = "address")
        batchFiles = files
        batch = OutgoingBatch(
            System.currentTimeMillis(), files.map { it.name }, files.sumOf { it.size },
            listOf(SendLeg(device.recipientHandle, device.name, SendRoute.NEARBY, LegState.CONNECTING)),
        )
        transferVisible = true
        sendLan(peer, files, fallBackToInternet = false)
    }

    private fun startLeg(handle: String, nearby: DesktopDiscovery.Peer?, files: List<OutgoingFile>) {
        if (nearby != null) {
            sendLan(nearby, files, fallBackToInternet = true)
        } else {
            updateLeg(handle) { it.copy(route = SendRoute.INTERNET, state = LegState.CONNECTING, progress = null, error = null) }
            wan.sendWAN(files, handle, deviceName, myHandle)
        }
    }

    /** Try a failed device again, by whichever route reaches it now. */
    fun retryLeg(handle: String) {
        val leg = batch?.legs?.firstOrNull { it.handle == handle } ?: return
        if (leg.active || batchFiles.isEmpty()) return
        val nearby = peers.firstOrNull { it.recipientHandle == handle }
        updateLeg(handle) {
            it.copy(route = if (nearby != null) SendRoute.NEARBY else SendRoute.INTERNET, state = LegState.CONNECTING, progress = null, error = null, refused = false)
        }
        startLeg(handle, nearby, batchFiles)
    }

    /** One LAN send with a few retries on network errors. Falls back to the internet if it fails. */
    private fun sendLan(peer: DesktopDiscovery.Peer, files: List<OutgoingFile>, fallBackToInternet: Boolean) {
        val h = peer.recipientHandle
        lanCancelled.remove(h)
        background("relaypony-lan-send") {
            val result = runCatching {
                val recipient = provider.recipientFromQr(h.toByteArray(Charsets.UTF_8))
                var attempt = 0
                var lastPost = 0L
                while (true) {
                    if (h in lanCancelled) throw java.io.IOException("stopped")
                    try {
                        SocketTransfer.sendTo(
                            peer.host, peer.port, provider, listOf(recipient), deviceName, myHandle, files,
                            peerMaxWire = peer.maxWire,
                            helloAuth = HelloAuth.signer(myScalar, myHandle, h),
                            onSocket = { lanSockets[h] = it },
                            onProgress = { sent, total ->
                                val now = System.currentTimeMillis()
                                if (now - lastPost >= 150 || sent >= total) {
                                    lastPost = now
                                    val p = if (total > 0) sent.toFloat() / total else 1f
                                    ui { updateLeg(h) { if (it.active) it.copy(state = LegState.SENDING, progress = p) else it } }
                                }
                            },
                        )
                        break
                    } catch (e: RefusedException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        if (h in lanCancelled) throw e
                        attempt++
                        if (attempt >= SEND_MAX_ATTEMPTS) throw e
                        Thread.sleep(SEND_RETRY_BASE_MS * attempt)
                    }
                }
            }
            lanSockets.remove(h)
            ui {
                val error = result.exceptionOrNull()
                when {
                    h in lanCancelled -> updateLeg(h) { it.copy(state = LegState.CANCELLED) }
                    result.isSuccess -> updateLeg(h) { it.copy(state = LegState.SENT, progress = 1f) }
                    // It's there but doesn't have this device paired: no point trying the internet.
                    error is RefusedException -> updateLeg(h) {
                        it.copy(state = LegState.FAILED, refused = true, error = I18n.t("xfer_refused", it.name))
                    }
                    // Discovery can be stale (the device left the network): try the internet. Say why,
                    // so a LAN problem isn't hidden behind a slower internet send.
                    fallBackToInternet && trust.isPinned(h) -> {
                        debug("LAN send to ${peer.name} at ${peer.host}:${peer.port} failed (${error}), trying the internet")
                        startLeg(h, null, files)
                    }
                    else -> updateLeg(h) {
                        it.copy(state = LegState.FAILED, error = "${peer.host}:${peer.port}: ${error?.message ?: error?.javaClass?.name ?: ""}")
                    }
                }
            }
        }
    }

    /** Map a WAN send's progress onto its leg. Ignored for legs going over the LAN. */
    private fun onWanLegStatus(peer: String, st: WanStatus) {
        updateLeg(peer) { leg ->
            if (leg.route == SendRoute.NEARBY) return@updateLeg leg
            when (st.kind) {
                WanStatusKind.CONNECTING -> leg.copy(route = SendRoute.INTERNET, state = LegState.CONNECTING)
                WanStatusKind.SENDING -> leg.copy(route = SendRoute.INTERNET_DIRECT, state = LegState.SENDING)
                WanStatusKind.SENDING_RELAY -> leg.copy(route = SendRoute.INTERNET_RELAY, state = LegState.SENDING)
                WanStatusKind.SENT -> leg.copy(state = LegState.SENT, progress = 1f)
                WanStatusKind.CANCELLED -> leg.copy(state = LegState.CANCELLED)
                WanStatusKind.SEND_FAILED, WanStatusKind.PREPARE_FAILED -> leg.copy(state = LegState.FAILED, error = st.arg)
                else -> leg
            }
        }
    }

    private fun updateLeg(handle: String, change: (SendLeg) -> SendLeg) {
        val b = batch ?: return
        if (b.legs.none { it.handle == handle }) return
        batch = b.copy(legs = b.legs.map { if (it.handle == handle) change(it) else it })
    }

    /** The Stop button: end every send still running. */
    fun stopSending() {
        val b = batch ?: return
        b.legs.filter { it.active }.forEach { leg ->
            if (leg.route == SendRoute.NEARBY) {
                lanCancelled.add(leg.handle)
                runCatching { lanSockets[leg.handle]?.close() }
                updateLeg(leg.handle) { it.copy(state = LegState.CANCELLED) }
            } else {
                wan.cancelSend(leg.handle)
                updateLeg(leg.handle) { if (it.active) it.copy(state = LegState.CANCELLED) else it }
            }
        }
    }

    /** Done on the transfer panel: the staged content is spent. */
    fun closeTransfer() {
        if (batch?.active == true) return
        batch = null
        batchFiles = emptyList()
        transferVisible = false
        staged.clear()
        sweepFolderZips()
    }

    /** "Send more": back to Home, with the same devices ticked for whatever is picked next. */
    fun sendMore() {
        val handles = batch?.legs?.map { it.handle } ?: emptyList()
        closeTransfer()
        sendToPreselect.clear(); sendToPreselect.addAll(handles)
    }

    // ---- Received content: folders and text drops ----

    /** The received file being extracted, by path. */
    var extracting by mutableStateOf<String?>(null); private set

    /** Extract a received .zip into Downloads/RelayPony (PROTOCOL_v3 section 12.4). */
    fun extractFolder(file: File) {
        if (extracting != null) return
        extracting = file.path
        background("relaypony-extract") {
            val result = runCatching { FolderExtractor.extract(file, file.name) }
            ui {
                extracting = null
                notice = result.fold(
                    { r ->
                        val where = r.folder.path.replaceFirst(System.getProperty("user.home"), "~")
                        if (r.skipped > 0) I18n.t("ext_done_skipped", r.files, where, r.skipped)
                        else I18n.t("ext_done", r.files, where)
                    },
                    { e -> extractProblem(file.name, e) },
                )
            }
        }
    }

    private fun extractProblem(name: String, e: Throwable): String {
        val why = when (e) {
            is FolderExtractor.EmptyArchiveException -> return I18n.t("ext_empty", name)
            is UnsafeArchiveException -> when (e.reason) {
                ZipRefusal.UNSAFE_PATH -> "ext_why_unsafe"
                ZipRefusal.ENCRYPTED -> "ext_why_encrypted"
                ZipRefusal.UNSUPPORTED_METHOD -> "ext_why_method"
                ZipRefusal.TOO_LARGE, ZipRefusal.TOO_MANY_ENTRIES, ZipRefusal.TOO_DEEP -> "ext_why_large"
                ZipRefusal.NO_SPACE -> "ext_why_space"
                else -> "ext_why_damaged"
            }
            else -> "ext_why_write"
        }
        return I18n.t("ext_failed", name, I18n.t(why))
    }

    /** What a small received .txt is: text to copy or a link to open, or null for anything else. */
    fun textDrop(file: File): ClipText.Sniff? {
        if (!ClipText.isCandidate(file.name, file.length())) return null
        return runCatching { ClipText.sniff(file.readBytes()) }.getOrNull()
    }

    /** File received files into the list, and say so. Event thread. */
    private fun recordReceived(files: List<File>, from: String) {
        val now = System.currentTimeMillis()
        val items = files.filter { it.exists() }.map { ReceivedItem(it, from, now) }
        items.forEach { received.add(0, it); receivedLog.put(it.file.path, it.from, it.atMs) }
        receiveProgress = null
        if (items.isNotEmpty()) notice = I18n.t("st_received", items.size, from)
    }

    /** Delete a received file from disk and from the list. */
    fun deleteReceived(item: ReceivedItem) {
        if (runCatching { item.file.delete() }.getOrDefault(false) || !item.file.exists()) {
            received.remove(item)
            receivedLog.remove(item.file.path)
        }
    }

    /** Open Downloads/RelayPony, where received files land. */
    fun openInbox() { runCatching { java.awt.Desktop.getDesktop().open(inbox) } }

    // ---- Language, first run, version ----

    fun setLanguage(code: String) {
        I18n.setLanguage(code)
        settings.lang = code
    }

    var showOnboarding by mutableStateOf(!settings.onboarded); private set
    fun finishOnboarding() { settings.onboarded = true; showOnboarding = false }
    fun replayOnboarding() { showOnboarding = true }

    /** The version jpackage stamps into installed builds; "dev" from ./gradlew run. */
    val appVersion: String = System.getProperty("jpackage.app-version")
        ?: DesktopController::class.java.`package`?.implementationVersion ?: "dev"

    // ---- Address book backup (paired devices only, no keypair) ----

    fun exportAddresses(passphrase: String, dest: File) {
        busy = true
        background("relaypony-export") {
            val result = runCatching { dest.outputStream().use { IdentityBackup.exportAddresses(passphrase, trust.all(), it) } }
            ui {
                busy = false
                result.onSuccess { notice = I18n.t("d_exported", dest.name) }.onFailure { fail("d_export_failed", it) }
            }
        }
    }

    fun importAddresses(passphrase: String, src: File) {
        busy = true
        background("relaypony-import") {
            val result = runCatching { src.inputStream().use { IdentityBackup.importAddresses(passphrase, it) } }
            ui {
                busy = false
                result.onSuccess { devices ->
                    devices.filter { it.recipientHandle != myHandle }.forEach { trust.pin(it.recipientHandle, it.name, it.pinnedAtEpochMs) }
                    refreshPaired()
                    notice = I18n.t("d_imported_addresses", devices.size)
                }.onFailure { fail("d_import_failed", it) }
            }
        }
    }

    // ---- Pair by code with a nearby RelayPony 3 device (one-way pins, as on Android) ----

    data class PendingVerify(val handle: String, val name: String, val sas: String)

    var pendingVerify by mutableStateOf<PendingVerify?>(null)

    fun stageDiscovered(peer: DesktopDiscovery.Peer) {
        pendingVerify = PendingVerify(peer.recipientHandle, peer.name, Sas.code(myHandle, peer.recipientHandle))
    }

    fun confirmVerify() {
        val pv = pendingVerify ?: return
        trust.pin(pv.handle, pv.name)
        refreshPaired()
        status = I18n.t("st_paired_with", pv.name)
        pendingVerify = null
    }

    fun stop() {
        runCatching { wan.stop() }
        runCatching { finder.close() }
        runCatching { server?.close() }
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
        p2pStatus = I18n.t("d_p2p_checking")
        Thread {
            val radio = WifiDirectLinux()
            val readiness = runCatching { radio.preflight() }.getOrElse {
                WifiDirectLinux.Readiness(false, listOf(it.message ?: "check failed"), emptyList())
            }
            radio.close()
            ui {
                p2pReadiness = readiness
                p2pBusy = false
                p2pStatus = I18n.t(if (readiness.usable) "d_p2p_available" else "d_p2p_unavailable")
            }
        }.apply { isDaemon = true; start() }
    }

    fun p2pFind() {
        if (p2pBusy) return
        p2pBusy = true
        p2pPeers.clear()
        p2pStatus = I18n.t("d_p2p_looking")
        Thread {
            val radio = p2p ?: WifiDirectLinux().also { p2p = it }
            runCatching {
                radio.startDiscovery { peer -> ui { if (p2pPeers.none { it.deviceAddress == peer.deviceAddress }) p2pPeers.add(peer) } }
                Thread.sleep(20_000)
                radio.knownPeers().forEach { peer ->
                    ui { if (p2pPeers.none { it.deviceAddress == peer.deviceAddress }) p2pPeers.add(peer) }
                }
                radio.stopDiscovery()
            }.onFailure { e -> ui { p2pStatus = I18n.t("d_search_failed", e.message ?: e.javaClass.simpleName) } }
            ui {
                p2pBusy = false
                p2pTranscript.clear(); p2pTranscript.addAll(radio.transcript())
                if (p2pStatus == I18n.t("d_p2p_looking")) {
                    p2pStatus = if (p2pPeers.isEmpty()) I18n.t("d_p2p_none") else I18n.t("d_found_devices", p2pPeers.size)
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
        p2pStatus = I18n.t("d_p2p_connecting", peer.name)
        Thread {
            val radio = p2p ?: WifiDirectLinux().also { p2p = it }
            runCatching {
                val group = radio.connect(peer.deviceAddress) { msg -> ui { p2pStatus = msg } }
                ui { p2pStatus = I18n.t("d_p2p_group_up", group.iface) }
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
                        is WifiDirectSession.Outcome.Sent -> I18n.t("st_wifi_sent", outcome.files, outcome.to)
                        is WifiDirectSession.Outcome.Received -> {
                            recordReceived(outcome.files, outcome.from)
                            I18n.t("st_wifi_received", outcome.files.size, outcome.from)
                        }
                    }
                }
            }.onFailure { e ->
                ui { sendProgress = null; p2pStatus = I18n.t("st_wifi_failed", e.message ?: e.javaClass.simpleName) }
            }
            runCatching { radio.disconnect() }
            ui { p2pBusy = false; p2pTranscript.clear(); p2pTranscript.addAll(radio.transcript()) }
        }.apply { isDaemon = true; start() }
    }

    fun p2pStop() {
        Thread { runCatching { p2p?.close() }; p2p = null }.apply { isDaemon = true; start() }
        p2pBusy = false
        p2pStatus = I18n.t("d_p2p_stopped")
    }

    private fun debug(msg: String) {
        if (debugOn) System.err.println("[relaypony ${java.time.LocalTime.now().withNano(0)}] $msg")
    }

    private fun background(name: String, block: () -> Unit) {
        Thread(block, name).apply { isDaemon = true; start() }
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

    private companion object {
        const val TAG_MEMORY_MS = 20 * 60 * 1000L
        const val MAX_REQUESTS = 10
        const val SEND_MAX_ATTEMPTS = 3
        const val SEND_RETRY_BASE_MS = 800L
    }
}
