package com.relaypony.desktop

import com.relaypony.crypto.AgeProvider
import com.relaypony.session.FileNames
import com.relaypony.session.OutgoingFile
import com.relaypony.session.SocketTransfer
import com.relaypony.session.pairing.QrPayload
import com.relaypony.session.pairing.Sas
import com.relaypony.transport.LocalInterfaces
import com.relaypony.transport.WireProtocol
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket

/**
 * relaypony — the desktop CLI (Linux · Windows · macOS), reusing the exact wire/session/crypto the
 * phones ship. Commands land incrementally; today: devices, receive, selftest.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null -> cmdGui()          // double-click / bare run opens the GUI
        "gui" -> cmdGui()
        "devices" -> cmdDevices()
        "receive" -> cmdReceive(args.drop(1))
        "send" -> cmdSend(args.drop(1))
        "pair" -> cmdPair()
        "selftest" -> cmdSelftest()
        "doctor" -> cmdDoctor()
        "p2p" -> cmdP2p(args.drop(1))
        else -> usage()
    }
}

/**
 * Wi-Fi Direct: no network, no router, no hotspot — the two radios talk to each other.
 *
 * `p2p check` first, always. Wi-Fi Direct depends on the driver advertising the right interface
 * modes and on being able to configure an interface, and when either is missing there is nothing
 * software can do about it; better to say so in one command than to fail mysteriously later.
 */
private fun cmdP2p(args: List<String>) {
    when (args.firstOrNull()) {
        "check" -> p2pCheck()
        "find" -> p2pFind()
        "send" -> p2pTransfer(args.drop(1))
        "receive" -> p2pTransfer(args.drop(1))
        else -> println(
            """
            Usage: relaypony p2p <command>

              check                        Report whether Wi-Fi Direct can work on this machine
              find                         List nearby Wi-Fi Direct devices
              send <files…> --peer <mac>   Form a group and send (peer MAC from `p2p find`)
              receive --peer <mac>         Form a group and wait to receive

            Wi-Fi Direct is Linux-only here, needs a P2P-capable driver, and needs permission to
            configure a network interface. Run `relaypony p2p check` first.
            """.trimIndent()
        )
    }
}

private fun p2pCheck() {
    val p2p = WifiDirectLinux()
    val r = p2p.preflight()
    println("Wi-Fi Direct: ${if (r.usable) "available" else "NOT available"}\n")
    if (r.notes.isNotEmpty()) {
        println("Details")
        r.notes.forEach { println("  · $it") }
    }
    if (r.blockers.isNotEmpty()) {
        println("\nBlockers")
        r.blockers.forEach { println("  ! $it") }
    }
    if (r.usable) {
        println("\nNext: relaypony p2p find")
    }
    p2p.close()
}

private fun p2pFind() {
    val p2p = WifiDirectLinux()
    val r = p2p.preflight()
    if (!r.usable) {
        println("Wi-Fi Direct is not available here. Run `relaypony p2p check` for why.")
        return
    }
    println("Looking for Wi-Fi Direct devices (~20s)…")
    println("(on the phone, open RelayPony and tap \"Find devices\" under Connect directly)")
    try {
        p2p.startDiscovery { peer -> println("  • ${peer.name}   ${peer.deviceAddress}") }
        Thread.sleep(20_000)
        val all = p2p.knownPeers()
        p2p.stopDiscovery()
        println(if (all.isEmpty()) "No devices found." else "\nFound ${all.size} device(s).")
        if (all.isNotEmpty()) {
            println("Send to one with:  relaypony p2p send <files…> --peer ${all.first().deviceAddress}")
        }
    } catch (e: Exception) {
        System.err.println("Discovery failed: ${e.message}")
        p2p.transcript().forEach { System.err.println("  $it") }
    } finally {
        p2p.close()
    }
}

/**
 * Form a group with a peer and run one transfer. Direction is decided by whether files were given:
 * the [com.relaypony.session.WifiIdent] handshake refuses if both sides want to send, or neither.
 */
private fun p2pTransfer(args: List<String>) {
    val peerMac = optionValue(args, "--peer")
    if (peerMac == null) {
        println("Which device? Pass --peer <mac> (get it from `relaypony p2p find`).")
        return
    }
    val valueOptions = setOf("--peer")
    val files = args.filterIndexed { i, a -> !a.startsWith("--") && args.getOrNull(i - 1) !in valueOptions }
        .map { File(it.replaceFirst("~", System.getProperty("user.home"))) }
    val missing = files.filter { !it.isFile }
    if (missing.isNotEmpty()) { println("Not found: ${missing.joinToString { it.path }}"); return }

    val provider = AgeProvider()
    val identity = FileIdentityStore(Config.identityFile).loadOrCreate(provider)
    val myHandle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
    val trust = FileTrustStore(Config.trustFile)
    val inbox = Config.defaultInbox()

    val p2p = WifiDirectLinux()
    try {
        val readiness = p2p.preflight()
        if (!readiness.usable) {
            println("Wi-Fi Direct is not available here:")
            readiness.blockers.forEach { println("  ! $it") }
            return
        }
        println("Connecting to $peerMac — accept the invitation on the phone…")
        val group = p2p.connect(peerMac) { println("  $it") }
        println("Group up on ${group.iface} (${if (group.isGroupOwner) "we own it" else "phone owns it"}).")

        val peerIp = p2p.resolvePeerAddress(group)
        println("Peer address: $peerIp")

        val outcome = WifiDirectSession.run(
            isGroupOwner = group.isGroupOwner,
            peerAddress = peerIp,
            provider = provider,
            identity = identity,
            deviceName = Config.deviceName,
            myHandle = myHandle,
            trust = trust,
            filesToSend = files,
            inbox = inbox,
            onStatus = { println("  $it") },
            onProgress = { sent, total ->
                if (total > 0) print("\r  ${sent * 100 / total}%   ")
            },
        )
        println()
        when (outcome) {
            is WifiDirectSession.Outcome.Sent -> println("Sent ${outcome.files} file(s) to ${outcome.to}.")
            is WifiDirectSession.Outcome.Received -> {
                println("Received ${outcome.files.size} file(s) from ${outcome.from}:")
                outcome.files.forEach { println("    ${it.absolutePath}") }
            }
        }
    } catch (e: Exception) {
        System.err.println("Wi-Fi Direct transfer failed: ${e.message}")
        System.err.println("What happened, in order:")
        p2p.transcript().forEach { System.err.println("  $it") }
    } finally {
        p2p.close()
    }
}

private fun usage() {
    println(
        """
        relaypony — encrypted device-to-device file transfer (desktop)

        Usage: relaypony <command>

          gui                      Open the graphical app (also opens when run with no command)
          receive [--dir <path>]   Receive files here; prints a pairing QR for phones to scan
                  [--port <n>]     Listen on a specific port (default ${Config.DEFAULT_PORT})
          send <files…> --to <n>   Send files to a paired device (by name or handle)
                  [--host <addr>]  Skip discovery and connect straight to an address
                  [--port <n>]     Port for --host (default ${Config.DEFAULT_PORT})
          pair                     Pair with a nearby device (compare the verification code)
          devices                  Discover nearby RelayPony devices on your network
          doctor                   Explain this machine's networking and what discovery can reach
          p2p <check|find|send>    Wi-Fi Direct: talk to a phone with no network at all (Linux)
          selftest                 Verify the crypto + wire stack runs on this JVM

        Environment: RELAYPONY_NAME (display name) · RELAYPONY_PORT (default listen port)
                     RELAYPONY_IFACE (comma-separated interfaces to use, e.g. wlan0)

        Devices are found three ways at once: mDNS, a UDP broadcast beacon that survives
        networks mDNS doesn't, and — via `doctor` — a scan of the local subnet. If none of
        them get through, `send --host <ip>` skips discovery entirely, and `p2p` needs no
        network at all.
        """.trimIndent()
    )
}

/**
 * Explain the network the way a person debugging a failed transfer needs it explained: which
 * interfaces we can advertise on, what our reachable addresses are, what we skipped and why, and
 * whether anything is actually answering out there.
 */
private fun cmdDoctor() {
    val port = Config.listenPort
    println("RelayPony doctor\n")

    println("This machine")
    println("  name:  ${Config.loadName()}")
    println("  port:  $port${if (System.getenv("RELAYPONY_PORT") != null) "  (from RELAYPONY_PORT)" else "  (default)"}")

    val endpoints = LocalNet.endpoints()
    println("\nInterfaces mDNS will use")
    if (endpoints.isEmpty()) {
        println("  (none — no connected, multicast-capable interface with an IPv4 address)")
    } else {
        endpoints.forEach { println("  • $it") }
    }

    val skipped = LocalNet.skipped()
    if (skipped.isNotEmpty()) {
        println("\nInterfaces skipped")
        skipped.forEach { println("  · ${it.ifaceName} — ${it.reason}") }
    }

    println("\nReach this machine at")
    if (endpoints.isEmpty()) println("  (nowhere — connect to a network first)")
    else LocalNet.reachableAt(port).forEach { println("  • $it") }

    val gateways = LocalInterfaces.defaultGateways().mapNotNull { it.hostAddress }
    if (gateways.isNotEmpty()) {
        // On a phone hotspot the gateway is the phone, which makes this the most useful line here.
        println("\nDefault gateway (on a phone hotspot, this is the phone)")
        gateways.forEach { println("  • $it") }
    }

    val finder = PeerFinder()
    println("\nSearching — mDNS browse and beacon probe (~6s)…")
    val found = finder.findOnce(6000).associateByTo(LinkedHashMap()) { it.recipientHandle }
    val diag = finder.diagnostics()

    println("\nmDNS bound on")
    if (diag.mdnsBound.isEmpty()) println("  (nothing)") else diag.mdnsBound.forEach { println("  • $it") }
    println("Beacon on UDP ${PeerFinder.BEACON_PORT} across all interfaces")
    if (diag.problems.isNotEmpty()) {
        println("\nProblems")
        diag.problems.forEach { println("  ! $it") }
    }

    println("\nPeers found: ${found.size}")
    found.values.forEach { p ->
        val reachable = if (LocalNet.probe(p.host, p.port)) "reachable" else "NOT reachable"
        val via = if (p.via.isNotEmpty()) " via ${p.via}" else ""
        println("  • ${p.name}  ${p.host}:${p.port}  wire v${p.maxWire}  [${p.source}$via] — $reachable")
        println("      ${p.recipientHandle}")
    }

    // The sweep can't identify anyone, so it is a diagnostic aid rather than a discovery source:
    // "something is listening there" is often the clue that discovery, not the peer, is broken.
    println("\nScanning the local subnet on port $port…")
    val hits = finder.sweep(port, found.values)
    finder.close()
    if (hits.isEmpty()) {
        println("  nothing else answered")
    } else {
        println("  ${hits.size} address(es) answering that discovery did not report:")
        hits.forEach { println("    • ${it.host}:${it.port}${if (it.isGateway) "  (gateway)" else ""}") }
        println("  Send to one with:  relaypony send <files…> --to \"<paired name>\" --host <ip>")
    }

    if (found.isEmpty() && hits.isEmpty()) {
        println(
            """

            No peers. The usual causes, in order:

              1. The other device isn't in Receive mode, so it isn't advertising.
              2. The other device is running a build without the beacon, and mDNS can't reach it.
                 Older builds are discoverable over mDNS only.
              3. A firewall is dropping discovery or the transfer port. On Linux:
                   firewalld:  sudo firewall-cmd --add-service=mdns \
                                 --add-port=${PeerFinder.BEACON_PORT}/udp --add-port=$port/tcp
                   ufw:        sudo ufw allow 5353/udp && sudo ufw allow ${PeerFinder.BEACON_PORT}/udp \
                                 && sudo ufw allow $port/tcp
              4. The access point isolates clients (common on guest and hotel Wi-Fi), which
                 blocks both multicast and the direct connection.

            Once you know the other device's address you don't need discovery at all:
              relaypony send file.jpg --to "<device name>" --host <ip> --port <port>
            """.trimIndent()
        )
    }
}

/** Browse mDNS for a few seconds and print the RelayPony devices found, deduped by handle. */
private fun cmdDevices() {
    val finder = PeerFinder()
    println("Looking for nearby RelayPony devices (~5s)…")
    println("(open the Receive tab on a phone so it advertises)")
    val found = finder.findOnce(5000).associateByTo(LinkedHashMap()) { it.recipientHandle }
    found.values.forEach { peer ->
        println("  • ${peer.name}  ${peer.host}:${peer.port}  wire v${peer.maxWire}  [${peer.source}]")
        println("      ${peer.recipientHandle}")
    }
    val diag = finder.diagnostics()
    finder.close()
    when (found.size) {
        0 -> {
            println("No devices found. Check that a phone is in Receive mode and on the same network.")
            println("Searched on: ${diag.mdnsBound.joinToString("; ").ifEmpty { "(no interface)" }} + beacon")
            diag.problems.forEach { println("  ! $it") }
            println("Run `relaypony doctor` for why, or `send --host` to skip discovery.")
        }
        1 -> println("Found 1 device.")
        else -> println("Found ${found.size} devices.")
    }
}

/** Advertise, print our pairing QR, and accept incoming transfers until interrupted. */
private fun cmdReceive(args: List<String>) {
    val provider = AgeProvider()
    val identity = FileIdentityStore(Config.identityFile).loadOrCreate(provider)
    val handle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
    val name = Config.deviceName
    val inbox = parseDir(args) ?: Config.defaultInbox()

    val qr = QrPayload(QrPayload.CURRENT_VERSION, provider.schemeId, handle, name).encode()
    println("Scan this on a phone to pair, then send to \"$name\":")
    TerminalQr.print(qr)
    println("Device:  $name")
    println("Handle:  $handle")
    println("Saving to: ${inbox.absolutePath}")

    val wanted = optionValue(args, "--port")?.toIntOrNull() ?: Config.listenPort
    val server = LocalNet.listen(wanted)
    val port = server.localPort
    if (wanted > 0 && port != wanted) {
        println("\nPort $wanted was busy — listening on $port instead.")
    }

    val finder = PeerFinder(handle)
    val advertisedOn = finder.advertise(port, name, handle)
    finder.start { }                       // listen so probes from other devices get answered
    val diag = finder.diagnostics()

    println()
    if (advertisedOn.isEmpty()) {
        System.err.println("! mDNS could not advertise on any interface — the UDP beacon is still")
        System.err.println("!   running, so newer builds can still find you.")
        diag.problems.forEach { System.err.println("!   $it") }
        System.err.println("!   Run `relaypony doctor` for the full picture.")
    } else {
        println("Discoverable on: ${advertisedOn.joinToString(", ")} (mDNS) + beacon on UDP ${PeerFinder.BEACON_PORT}")
    }

    println("Reach this machine at:")
    val reachable = LocalNet.reachableAt(port)
    if (reachable.isEmpty()) println("  (no connected network interface)")
    else reachable.forEach { println("  • $it") }
    println("  If the phone can't find you, send from another computer with:")
    println("    relaypony send <file> --to \"$name\" --host <one of the addresses above> --port $port")

    println("\nListening on port $port — Ctrl-C to stop.\n")

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { finder.close() }
        runCatching { server.close() }
    })

    while (!server.isClosed) {
        val written = LinkedHashMap<String, File>()   // manifest name -> destination file
        try {
            val result = SocketTransfer.receiveOnceFrom(
                server, provider, identity,
                deviceName = name, recipientHandle = handle,
            ) { entry ->
                val dest = uniqueFile(inbox, FileNames.sanitize(entry.name))
                written[entry.name] = dest
                dest.outputStream()
            }
            println("Received ${result.manifest.files.size} file(s) from ${result.senderName}:")
            for (f in result.manifest.files) {
                val dest = written[f.name]
                if (dest != null && dest.exists()) {
                    println("    ${dest.absolutePath} (${dest.length()} bytes)")
                } else {
                    println("    ! not written to disk: ${f.name}")
                }
            }
            println()
        } catch (e: Exception) {
            if (server.isClosed) break
            System.err.println("Transfer failed: ${e.message}")
        }
    }
}

private fun cmdSelftest() {
    println("Wire: this build speaks up to v${WireProtocol.MAX_WIRE_VERSION}")
    val provider = AgeProvider()
    val identity = provider.generateIdentity()
    val handle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
    println("Generated identity: $handle")
    println(if (handle.startsWith("age1")) "OK — crypto + wire + session run on this JVM." else "Unexpected handle.")
}

/** Pair with nearby unpaired devices: show each one's verification code and pin on confirmation. */
private fun cmdPair() {
    val provider = AgeProvider()
    val identity = FileIdentityStore(Config.identityFile).loadOrCreate(provider)
    val myHandle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
    val trust = FileTrustStore(Config.trustFile)

    val finder = PeerFinder(myHandle)
    println("Looking for nearby devices to pair (~5s)…")
    println("(open the Receive tab on the phone so it advertises)")
    val seen = finder.findOnce(5000)
        .filterNot { trust.isPinned(it.recipientHandle) }
        .associateByTo(LinkedHashMap()) { it.recipientHandle }
    finder.close()

    if (seen.isEmpty()) { println("No new devices found (already-paired ones are skipped)."); return }
    for (p in seen.values) {
        val sas = Sas.code(myHandle, p.recipientHandle)
        println()
        println("Pair with \"${p.name}\"?")
        println("  handle:            ${p.recipientHandle}")
        println("  verification code: $sas")
        println("  Confirm this matches the six digits shown on ${p.name}.")
        print("  Pair? [y/N] ")
        val ans = readlnOrNull()?.trim()?.lowercase()
        if (ans == "y" || ans == "yes") {
            trust.pin(p.recipientHandle, p.name)
            println("  Paired with ${p.name}.")
        } else {
            println("  Skipped.")
        }
    }
}

/**
 * Send files to a device. Usage: send <files…> --to <name|handle> [--host <addr> [--port <n>]]
 *
 * Two ways to find the other side. By default we browse mDNS, which is the nice path when it
 * works. With `--host` we skip discovery entirely and connect to an address you supply — the
 * escape hatch for every network where multicast doesn't survive the trip (phone hotspots, guest
 * Wi-Fi with client isolation, VPNs). Discovery is a convenience; it should never be the only way
 * to complete a transfer.
 */
private fun cmdSend(args: List<String>) {
    val to = optionValue(args, "--to")
    if (to == null) {
        println("Usage: relaypony send <files…> --to <name|handle> [--host <addr>] [--port <n>]")
        return
    }
    // Every option that takes a value, so its value is never mistaken for a filename.
    val valueOptions = setOf("--to", "--host", "--port")
    val files = args.filterIndexed { i, a -> !a.startsWith("--") && args.getOrNull(i - 1) !in valueOptions }
        .map { File(it.replaceFirst("~", System.getProperty("user.home"))) }
    if (files.isEmpty()) { println("No files to send."); return }
    val missing = files.filter { !it.isFile }
    if (missing.isNotEmpty()) { println("Not found: ${missing.joinToString { it.path }}"); return }

    val provider = AgeProvider()
    val identity = FileIdentityStore(Config.identityFile).loadOrCreate(provider)
    val myHandle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
    val trust = FileTrustStore(Config.trustFile)

    val host = optionValue(args, "--host")
    val target = if (host != null) {
        manualTarget(to, host, optionValue(args, "--port")?.toIntOrNull(), trust) ?: return
    } else {
        discoveredTarget(to, trust) ?: return
    }

    val recipient = provider.recipientFromQr(target.recipientHandle.toByteArray(Charsets.UTF_8))
    val outgoing = files.map { f -> OutgoingFile(f.name, guessMime(f.name), f.length()) { FileInputStream(f) } }
    val total = files.sumOf { it.length() }
    println("Sending ${files.size} file(s) ($total bytes) to ${target.name} at ${target.host}:${target.port}…")
    try {
        SocketTransfer.sendTo(
            target.host, target.port, provider, listOf(recipient), Config.deviceName, myHandle, outgoing,
            peerMaxWire = target.maxWire,
        ) { sent, tot ->
            val pct = if (tot > 0) sent * 100 / tot else 100
            print("\r  $pct%   ")
        }
        println("\rSent ${files.size} file(s) to ${target.name}.   ")
    } catch (e: Exception) {
        println()
        System.err.println("Send failed: ${e.message}")
        if (host != null) {
            System.err.println("  Check the receiver is running and that $host:${target.port} is open.")
            System.err.println("  Its `relaypony receive` banner prints the exact address and port.")
        } else {
            System.err.println("  Run `relaypony doctor` to check reachability, or retry with --host.")
        }
    }
}

/** Find the target by browsing mDNS — the default path when the network cooperates. */
private fun discoveredTarget(to: String, trust: FileTrustStore): DesktopDiscovery.Peer? {
    println("Looking for \"$to\" (~4s)…")
    val finder = PeerFinder()
    val peers = finder.findOnce(4000)
    val diag = finder.diagnostics()
    finder.close()

    val target = peers.firstOrNull { it.recipientHandle == to || it.name.equals(to, ignoreCase = true) }
    if (target == null) {
        println("Device \"$to\" not found. Is it in Receive mode on the same network?")
        diag.problems.forEach { println("  ! $it") }
        println("  Tried mDNS and the UDP beacon. `relaypony doctor` also scans the subnet.")
        println("  If you know the address:  relaypony send <files…> --to \"$to\" --host <ip>")
        println("  Run `relaypony doctor` for the details.")
        return null
    }
    if (!trust.isPinned(target.recipientHandle)) {
        println("Not paired with ${target.name}. Run: relaypony pair")
        return null
    }
    return target
}

/**
 * Build the target from an address the user supplied, with no discovery at all.
 *
 * We still need the peer's public key to encrypt to, and that is the one thing an IP address can't
 * tell us — so it comes from the trust store (paired by name) or straight from an `age1…` handle
 * passed to `--to`. A raw handle is an out-of-band key the user typed deliberately, so we allow it
 * unpinned, but we say so rather than letting it look like an established pairing.
 */
private fun manualTarget(
    to: String,
    host: String,
    port: Int?,
    trust: FileTrustStore,
): DesktopDiscovery.Peer? {
    val explicitHandle = to.startsWith("age1")
    val pinned = trust.all().firstOrNull { it.recipientHandle == to || it.name.equals(to, ignoreCase = true) }
    val handle = pinned?.recipientHandle ?: to.takeIf { explicitHandle }

    if (handle == null) {
        println("Don't know the key for \"$to\", so there's nothing to encrypt to.")
        println("Pair with it first (relaypony pair), or pass its age1… handle to --to.")
        val known = trust.all()
        if (known.isNotEmpty()) println("Paired devices: ${known.joinToString { it.name }}")
        return null
    }
    if (pinned == null) {
        println("Note: $handle isn't paired — sending to the key you supplied on the command line.")
    }

    // We can't ask an undiscovered peer what it speaks, so use v1: the frozen, always-compatible
    // floor. Every build since 1.0 understands it.
    return DesktopDiscovery.Peer(
        // A raw age1 handle is 62 characters of noise in a progress line; abbreviate it.
        name = pinned?.name ?: if (explicitHandle) "${to.take(12)}…" else to,
        host = host,
        port = port ?: Config.listenPort,
        recipientHandle = handle,
        maxWire = 1,
    )
}

private fun optionValue(args: List<String>, opt: String): String? {
    val i = args.indexOf(opt)
    if (i >= 0 && i + 1 < args.size) return args[i + 1]
    return args.firstOrNull { it.startsWith("$opt=") }?.substringAfter("=")
}

private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "heic" -> "image/heic"
    "pdf" -> "application/pdf"
    "txt" -> "text/plain"
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}

// --- helpers ---

private fun parseDir(args: List<String>): File? {
    val i = args.indexOf("--dir")
    val path = when {
        i >= 0 && i + 1 < args.size -> args[i + 1]
        else -> args.firstOrNull { it.startsWith("--dir=") }?.substringAfter("=")
    } ?: return null
    return File(path.replaceFirst("~", System.getProperty("user.home"))).apply { mkdirs() }
}

private fun uniqueFile(dir: File, name: String): File {
    dir.mkdirs()
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (candidate.exists()) { candidate = File(dir, "$base ($i)$ext"); i++ }
    return candidate
}
