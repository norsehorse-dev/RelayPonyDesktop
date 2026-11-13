package com.relaypony.desktop

import com.relaypony.crypto.AgeProvider
import com.relaypony.session.FileNames
import com.relaypony.session.OutgoingFile
import com.relaypony.session.SocketTransfer
import com.relaypony.session.HelloAuth
import com.relaypony.session.RefusedException
import com.relaypony.session.TransferLimits
import com.relaypony.session.pairing.LanPair
import com.relaypony.session.pairing.PairingFlow
import com.relaypony.session.pairing.PairingService
import com.relaypony.session.pairing.SasV2
import com.relaypony.session.wan.RelayClient
import com.relaypony.session.wan.RelayUrls
import com.relaypony.transport.LocalInterfaces
import com.relaypony.transport.WireProtocol
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        "pair" -> cmdPair(args.drop(1))
        "selftest" -> cmdSelftest()
        "doctor" -> cmdDoctor()
        "relay-check" -> cmdRelayCheck()
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
        println("Connecting to $peerMac. Accept the invitation on the phone…")
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
        relaypony: encrypted device-to-device file transfer (desktop)

        Usage: relaypony <command>

          gui                      Open the graphical app (also opens when run with no command)
          receive [--dir <path>]   Receive files here from paired devices
                  [--port <n>]     Listen on a specific port (default ${Config.DEFAULT_PORT})
                  [--accept-unpaired]  Also take files from devices that aren't paired
          send <files…> --to <n>   Send files to a paired device (by name or handle)
                  [--host <addr>]  Skip discovery and connect straight to an address
                  [--port <n>]     Port for --host (default ${Config.DEFAULT_PORT})
          pair                     Pair with another device: shows a QR to scan, then a code to compare
                  [--code <text>]  Use the other device's pairing code instead
                  [--words]        Show a word code for the other device to type
                  [--word-code <code>] [--relay <url>]  Pair with a word code from the other device
          devices                  Discover nearby RelayPony devices on your network
          doctor                   Explain this machine's networking and what discovery can reach
          relay-check              Test every relay call internet transfers make, from this computer
          p2p <check|find|send>    Wi-Fi Direct: talk to a phone with no network at all (Linux)
          selftest                 Verify the crypto + wire stack runs on this JVM

        Environment: RELAYPONY_NAME (display name) · RELAYPONY_PORT (default listen port)
                     RELAYPONY_IFACE (comma-separated interfaces to use, e.g. wlan0)

        Devices are found three ways at once: mDNS, a UDP broadcast beacon that survives
        networks mDNS doesn't, and (via `doctor`) a scan of the local subnet. If none of
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
        println("  (none: no connected, multicast-capable interface with an IPv4 address)")
    } else {
        endpoints.forEach { println("  • $it") }
    }

    val skipped = LocalNet.skipped()
    if (skipped.isNotEmpty()) {
        println("\nInterfaces skipped")
        skipped.forEach { println("  · ${it.ifaceName}: ${it.reason}") }
    }

    println("\nReach this machine at")
    if (endpoints.isEmpty()) println("  (nowhere: connect to a network first)")
    else LocalNet.reachableAt(port).forEach { println("  • $it") }

    val gateways = LocalInterfaces.defaultGateways().mapNotNull { it.hostAddress }
    if (gateways.isNotEmpty()) {
        // On a phone hotspot the gateway is the phone, which makes this the most useful line here.
        println("\nDefault gateway (on a phone hotspot, this is the phone)")
        gateways.forEach { println("  • $it") }
    }

    val finder = PeerFinder()
    println("\nSearching: mDNS browse and beacon probe (~6s)…")
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
        println("  • ${p.name}  ${p.host}:${p.port}  wire v${p.maxWire}  [${p.source}$via]  $reachable")
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
    val myScalar = provider.scalarOf(identity)
    val trust = FileTrustStore(Config.trustFile)
    val settings = DesktopSettings()
    val name = Config.loadName()
    val inbox = parseDir(args) ?: Config.defaultInbox()
    val acceptUnpaired = "--accept-unpaired" in args || settings.acceptUnpaired

    println("Device:  $name")
    println("Handle:  $handle")
    println("Saving to: ${inbox.absolutePath}")
    println(
        if (acceptUnpaired) "Accepting files from any device on this network."
        else "Accepting files from paired devices only. Pair one with `relaypony pair`."
    )

    val wanted = optionValue(args, "--port")?.toIntOrNull() ?: Config.listenPort
    val server = LocalNet.listen(wanted)
    val port = server.localPort
    if (wanted > 0 && port != wanted) {
        println("\nPort $wanted was busy, so this is listening on $port instead.")
    }

    // Not pair-capable: this command doesn't take pairing messages, so peers shouldn't send them here.
    val finder = PeerFinder(handle)
    val advertisedOn = finder.advertise(port, name, handle, pairCapable = false)
    finder.start { }                       // listen so probes from other devices get answered
    val diag = finder.diagnostics()

    println()
    if (advertisedOn.isEmpty()) {
        System.err.println("! mDNS could not advertise on any interface. The UDP beacon is still")
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
    println("  If the other device can't find you, send from another computer with:")
    println("    relaypony send <file> --to \"$name\" --host <one of the addresses above> --port $port")

    println("\nListening on port $port. Ctrl-C to stop.\n")

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { finder.close() }
        runCatching { server.close() }
    })

    // Paired-only receive (PROTOCOL_v3 section 9.2), the same rule as the GUI and the phones.
    val seenTags = HashMap<String, Long>()
    val gate: (WireProtocol.Hello) -> String? = gate@{ hello ->
        val from = hello.recipientHandle
        val pinned = trust.isPinned(from)
        val auth = hello.auth
        val now = System.currentTimeMillis()
        if (pinned && auth != null) {
            val key = auth.tag.joinToString("") { "%02x".format(it) }
            seenTags.entries.removeAll { now - it.value > 20 * 60 * 1000L }
            if (HelloAuth.verify(myScalar, handle, from, auth, now) && seenTags.put(key, now) == null) {
                settings.markTaggedPeer(from)
                return@gate null
            }
        } else {
            if (pinned && !settings.isTaggedPeer(from)) return@gate null
            if (!pinned && acceptUnpaired) return@gate null
        }
        System.err.println("Refused files from \"${hello.deviceName.take(64)}\": it isn't paired with this computer.")
        "unpaired"
    }

    while (!server.isClosed) {
        val written = LinkedHashMap<String, File>()   // manifest name -> destination file
        try {
            val result = SocketTransfer.acceptOne(
                server, provider, identity,
                onPair = { },
                deviceName = name, recipientHandle = handle,
                limits = TransferLimits(freeBytes = { inbox.usableSpace }),
                gate = gate,
            ) { entry ->
                val dest = uniqueFile(inbox, FileNames.sanitize(entry.name))
                written[entry.name] = dest
                dest.outputStream()
            } ?: continue
            val from = trust.get(result.senderHandle)?.name ?: "${result.senderName} (not paired)"
            println("Received ${result.manifest.files.size} file(s) from $from:")
            for (f in result.manifest.files) {
                val dest = written[f.name]
                if (dest != null && dest.exists()) {
                    println("    ${dest.absolutePath} (${dest.length()} bytes)")
                } else {
                    println("    ! not written to disk: ${f.name}")
                }
            }
            println()
        } catch (e: RefusedException) {
            continue
        } catch (e: Exception) {
            written.values.forEach { runCatching { it.delete() } }
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
    println(if (handle.startsWith("age1")) "OK: crypto + wire + session run on this JVM." else "Unexpected handle.")
}

/**
 * Pair with another device (PROTOCOL_v3 sections 3 and 4). With no arguments this prints a QR for
 * the other device to scan, then asks you to compare the code both show. With `--code <text>` it
 * uses the other device's pairing code instead (the text of its QR).
 */
private fun cmdPair(args: List<String>) {
    if ("--words" in args || optionValue(args, "--word-code") != null) {
        cmdPairWords(showCode = "--words" in args, code = optionValue(args, "--word-code"), relayArg = optionValue(args, "--relay"))
        return
    }
    val provider = AgeProvider()
    val identity = FileIdentityStore(Config.identityFile).loadOrCreate(provider)
    val myHandle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
    val myScalar = provider.scalarOf(identity)
    val trust = FileTrustStore(Config.trustFile)
    val routes = FilePeerRouteStore()
    val settings = DesktopSettings()
    val name = Config.loadName()
    val myRelay = RelayUrls.normalize(settings.relayServer)
    val relay = RelayClient(RelayUrls.base(myRelay))
    val main = ExecutorMainThread()
    val finder = PeerFinder(myHandle)
    val done = CountDownLatch(1)
    var exitCode = 1

    val service = PairingService(
        provider = provider,
        identity = identity,
        myScalar = myScalar,
        myHandle = myHandle,
        myName = { name },
        myInbox = { settings.inboxId },
        myRelay = { myRelay },
        trust = trust,
        routes = routes,
        mailer = { peer, r, inboxId, payload ->
            Thread { runCatching { RelayClient(RelayUrls.base(r)).mboxSend(inboxId, payload) } }.apply { isDaemon = true; start() }
            Thread {
                runCatching {
                    finder.probe(1500) { p ->
                        if (p.recipientHandle == peer && p.pairCapable) runCatching { LanPair.send(p.host, p.port, payload) }
                    }
                }
            }.apply { isDaemon = true; start() }
        },
    )
    service.onEvent = { ev ->
        when (ev) {
            is PairingService.Event.Request -> {
                println()
                println("\"${ev.pending.request.name.take(64)}\" wants to pair.")
                println("  Code: ${SasV2.display(ev.pending.code)}")
                print("  Does the other device show the same code? [y/N] ")
                val ans = readlnOrNull()?.trim()?.lowercase()
                val yes = ans == "y" || ans == "yes"
                service.confirm(ev.pending, yes)
                if (!yes) { println("  Cancelled."); done.countDown() }
            }
            is PairingService.Event.Waiting -> {
                println("Code: ${SasV2.display(ev.code)}")
                println("Confirm on ${ev.name} if it shows the same code. Waiting…")
            }
            is PairingService.Event.Paired -> { println("Paired with ${ev.name}."); exitCode = 0; done.countDown() }
            is PairingService.Event.PairedOneWay -> {
                println("Paired with ${ev.name}. It runs an older RelayPony, so pair from that device too.")
                exitCode = 0; done.countDown()
            }
            is PairingService.Event.Declined -> { println("The other device cancelled."); done.countDown() }
            is PairingService.Event.Unpaired -> println("${ev.name} unpaired this computer.")
            is PairingService.Event.RouteUpdated -> Unit
        }
    }

    // The LAN listener takes PAIR frames only; every transfer is refused while pairing.
    val server = runCatching { LocalNet.listen(Config.listenPort) }.getOrNull()
    if (server != null) {
        finder.advertise(server.localPort, name, myHandle, pairCapable = true)
        Thread {
            while (!server.isClosed) {
                runCatching {
                    SocketTransfer.acceptOne(
                        server, provider, identity,
                        onPair = { sealed -> main.post { service.onLanPair(sealed) } },
                        gate = { "pairing" },
                    ) { throw java.io.IOException("not receiving") }
                }
            }
        }.apply { isDaemon = true; start() }
    }
    finder.start { }

    // The relay copy: poll this device's inbox.
    Thread {
        val mailboxes = relay.info()?.hasMailboxes == true
        if (!mailboxes) System.err.println("(The relay has no private inboxes, so pairing works over this network only.)")
        while (mailboxes && done.count > 0) {
            runCatching { relay.mboxPoll(settings.inboxId) }.getOrNull()?.forEach { blob ->
                main.post { service.open(blob)?.let { service.onSealedPlain(it) } }
            }
            Thread.sleep(2000)
        }
    }.apply { isDaemon = true; start() }

    val code = optionValue(args, "--code")
    main.post {
        if (code != null) {
            try {
                service.onScanned(code)
            } catch (e: IllegalArgumentException) {
                println(if (code.contains(myHandle)) "That's this computer's own code." else "That isn't a RelayPony pairing code.")
                done.countDown()
            }
        } else {
            val qr = service.showQr()
            println("Scan this with RelayPony on the other device:")
            TerminalQr.print(qr.encode())
            println("Or, on another computer: relaypony pair --code '${qr.encode()}'")
            println("The code is good for five minutes. Waiting…")
        }
    }

    if (!done.await(PairingFlow.VALIDITY_MS + 30_000, TimeUnit.MILLISECONDS)) println("Nothing arrived in time. Run `relaypony pair` again.")
    runCatching { server?.close() }
    runCatching { finder.close() }
    main.shutdown()
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
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
            target.host, target.port, provider, listOf(recipient), Config.loadName(), myHandle, outgoing,
            peerMaxWire = target.maxWire,
            // The signed HELLO (PROTOCOL_v3 section 9.1): a 4.0 receiver takes paired senders by it.
            helloAuth = HelloAuth.signer(provider.scalarOf(identity), myHandle, target.recipientHandle),
        ) { sent, tot ->
            val pct = if (tot > 0) sent * 100 / tot else 100
            print("\r  $pct%   ")
        }
        println("\rSent ${files.size} file(s) to ${target.name}.   ")
    } catch (e: RefusedException) {
        println()
        System.err.println("${target.name} refused the files: it doesn't have this computer paired.")
        System.err.println("  Pair them with `relaypony pair`, then send again.")
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
        println("Note: $handle isn't paired. Sending to the key you supplied on the command line.")
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

internal fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
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
