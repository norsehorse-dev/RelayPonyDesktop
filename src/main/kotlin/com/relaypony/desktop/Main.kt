package com.relaypony.desktop

import com.relaypony.crypto.AgeProvider
import com.relaypony.session.FileNames
import com.relaypony.session.OutgoingFile
import com.relaypony.session.SocketTransfer
import com.relaypony.session.pairing.QrPayload
import com.relaypony.session.pairing.Sas
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
        else -> usage()
    }
}

private fun usage() {
    println(
        """
        relaypony — encrypted device-to-device file transfer (desktop)

        Usage: relaypony <command>

          gui                      Open the graphical app (also opens when run with no command)
          receive [--dir <path>]   Receive files here; prints a pairing QR for phones to scan
          send <files…> --to <n>   Send files to a paired device (by name or handle)
          pair                     Pair with a nearby device (compare the verification code)
          devices                  Discover nearby RelayPony devices on your network
          selftest                 Verify the crypto + wire stack runs on this JVM
        """.trimIndent()
    )
}

/** Browse mDNS for a few seconds and print the RelayPony devices found, deduped by handle. */
private fun cmdDevices() {
    val discovery = DesktopDiscovery()
    val found = LinkedHashMap<String, DesktopDiscovery.Peer>()
    println("Looking for nearby RelayPony devices (~5s)…")
    println("(open the Receive tab on a phone so it advertises)")
    discovery.browse { peer ->
        if (found.putIfAbsent(peer.recipientHandle, peer) == null) {
            println("  • ${peer.name}  ${peer.host}:${peer.port}  wire v${peer.maxWire}")
            println("      ${peer.recipientHandle}")
        }
    }
    Thread.sleep(5000)
    discovery.close()
    when (found.size) {
        0 -> println("No devices found. Check that a phone is in Receive mode and on the same network.")
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

    val server = ServerSocket(0)
    val port = server.localPort
    val discovery = DesktopDiscovery()
    discovery.advertise("RelayPony-$port", port, name, handle)
    println("Listening on port $port — Ctrl-C to stop.\n")

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { discovery.close() }
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

    val discovery = DesktopDiscovery()
    val seen = LinkedHashMap<String, DesktopDiscovery.Peer>()
    println("Looking for nearby devices to pair (~5s)…")
    println("(open the Receive tab on the phone so it advertises)")
    discovery.browse { p -> if (!trust.isPinned(p.recipientHandle)) seen.putIfAbsent(p.recipientHandle, p) }
    Thread.sleep(5000)
    discovery.close()

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

/** Send files to a paired device found on the network. Usage: send <files…> --to <name|handle> */
private fun cmdSend(args: List<String>) {
    val to = optionValue(args, "--to")
    if (to == null) { println("Usage: relaypony send <files…> --to <name|handle>"); return }
    val files = args.filterIndexed { i, a -> !a.startsWith("--") && args.getOrNull(i - 1) != "--to" }
        .map { File(it.replaceFirst("~", System.getProperty("user.home"))) }
    if (files.isEmpty()) { println("No files to send."); return }
    val missing = files.filter { !it.isFile }
    if (missing.isNotEmpty()) { println("Not found: ${missing.joinToString { it.path }}"); return }

    val provider = AgeProvider()
    val identity = FileIdentityStore(Config.identityFile).loadOrCreate(provider)
    val myHandle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
    val trust = FileTrustStore(Config.trustFile)

    println("Looking for \"$to\" (~4s)…")
    val discovery = DesktopDiscovery()
    val peers = LinkedHashMap<String, DesktopDiscovery.Peer>()
    discovery.browse { p -> peers.putIfAbsent(p.recipientHandle, p) }
    Thread.sleep(4000)
    discovery.close()

    val target = peers.values.firstOrNull { it.recipientHandle == to || it.name.equals(to, ignoreCase = true) }
    if (target == null) { println("Device \"$to\" not found. Is it in Receive mode on the same network?"); return }
    if (!trust.isPinned(target.recipientHandle)) { println("Not paired with ${target.name}. Run: relaypony pair"); return }

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
    }
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
