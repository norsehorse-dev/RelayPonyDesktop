package com.relaypony.desktop

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Wi-Fi Direct on Linux, driven through `wpa_cli`.
 *
 * The phones already speak Wi-Fi Direct to each other; what stopped a laptop joining in was simply
 * that nothing on this side implemented P2P. Android's Wi-Fi Direct is ordinary Wi-Fi P2P, so a
 * Linux machine whose driver advertises `P2P-GO` and `P2P-client` can form a group with a phone and
 * then speak the exact handshake the Android build already uses: [com.relaypony.session.WifiIdent]
 * on port 8987, then a normal session on 8988. That second half is free — it is the same code.
 *
 * Why `wpa_cli` and not something tidier: wpa_supplicant's control interface is a UNIX *datagram*
 * socket, and the JDK's `UnixDomainSocketAddress` only does SOCK_STREAM, so there is no way to talk
 * to it directly from the JVM. D-Bus would mean a native dependency. Shelling out to the tool every
 * distro already ships is the honest trade.
 *
 * What this cannot paper over. P2P is privileged, driver-dependent and genuinely fragile:
 *
 *  * The control socket is usually root-only unless the deployment set `GROUP=` in
 *    wpa_supplicant.conf, so we look for `sudo -n` or `pkexec` and say plainly when neither works.
 *  * NetworkManager has its own Wi-Fi-P2P support and will race us for the group interface. If P2P
 *    misbehaves under NM, `[keyfile] unmanaged-devices=interface-name:p2p-*` is the fix.
 *  * wpa_supplicant creates the group interface but does no IP configuration at all. We use the
 *    addresses from the EAPOL exchange when the peer offers them, fall back to a DHCP client, and
 *    otherwise explain exactly which command is missing rather than failing silently.
 *
 * Every step reports what it tried, because "Wi-Fi Direct didn't work" is useless to a bug report.
 */
class WifiDirectLinux {

    // ---- Types -------------------------------------------------------------------------------

    /** A peer as wpa_supplicant sees it. [deviceAddress] is what `p2p_connect` takes. */
    data class P2pPeer(val deviceAddress: String, val name: String)

    /** A formed group, as parsed from P2P-GROUP-STARTED. */
    data class Group(
        val iface: String,
        val isGroupOwner: Boolean,
        /** Group owner's IP, when the peer supplied it via EAPOL. Null means we must find it. */
        val goIpAddr: String?,
        /** The address the peer allocated to us over EAPOL, if any. */
        val myIpAddr: String?,
        val ipMask: String?,
    )

    /** The result of [preflight]: can we even try, and what exactly is missing if not. */
    data class Readiness(
        val usable: Boolean,
        val blockers: List<String>,
        val notes: List<String>,
    )

    class P2pException(message: String) : Exception(message)

    // ---- State -------------------------------------------------------------------------------

    private var socketArg: List<String> = emptyList()      // e.g. ["-g", "/run/wpa_supplicant/global"]
    private var monitor: Process? = null
    private val events = LinkedBlockingQueue<String>()
    private val log = CopyOnWriteArrayList<String>()
    private val peers = LinkedHashMap<String, P2pPeer>()
    @Volatile private var group: Group? = null

    /** Everything we did and saw, in order — the thing to paste into an issue. */
    fun transcript(): List<String> = log.toList()

    private fun note(line: String) {
        if (log.size < MAX_LOG) log.add(line)
    }

    // ---- Preflight ---------------------------------------------------------------------------

    /**
     * Check every precondition before touching the radio, so a failure names its own cause.
     * Deliberately cheap and side-effect free apart from a one-second `p2p_find`.
     */
    fun preflight(): Readiness {
        val blockers = ArrayList<String>()
        val notes = ArrayList<String>()

        if (!isLinux()) {
            return Readiness(
                usable = false,
                blockers = listOf(
                    "Wi-Fi Direct is Linux-only in RelayPony. macOS has no public API for it " +
                        "(AWDL is private) and the Windows WinRT API isn't reachable from the JVM."
                ),
                notes = emptyList(),
            )
        }

        val wpaCli = which("wpa_cli")
        if (wpaCli == null) {
            blockers.add("wpa_cli not found on PATH. Install wpasupplicant (Debian/Ubuntu) or wpa_supplicant (Arch).")
        } else {
            notes.add("wpa_cli: $wpaCli")
        }

        val socket = findControlSocket()
        if (socket == null) {
            blockers.add(controlSocketBlocker())
        } else {
            socketArg = socket
            notes.add("control interface: ${socket.joinToString(" ")}")
        }

        // Driver capability. This is the decisive test: wpa_supplicant enables P2P only when the
        // phy advertises BOTH of these interface modes.
        when (val modes = supportedInterfaceModes()) {
            null -> notes.add("iw not installed — could not confirm driver P2P support in advance.")
            else -> {
                val go = modes.any { it.equals("P2P-GO", ignoreCase = true) }
                val client = modes.any { it.equals("P2P-client", ignoreCase = true) }
                if (go && client) {
                    notes.add("driver advertises P2P-GO and P2P-client")
                } else {
                    blockers.add(
                        "This wireless driver does not support Wi-Fi Direct — 'iw list' does not " +
                            "advertise ${if (!go) "P2P-GO" else ""}${if (!go && !client) " and " else ""}" +
                            "${if (!client) "P2P-client" else ""}. Nothing in software can work around that."
                    )
                }
            }
        }

        if (blockers.isEmpty()) {
            // The only definitive check: ask wpa_supplicant. Note wpa_cli exits 0 even for FAIL,
            // so the reply text is the answer, never the exit code.
            val reply = wpa("p2p_find", "1")
            when {
                reply.equals("OK", ignoreCase = true) -> notes.add("wpa_supplicant accepted p2p_find")
                reply.contains("UNKNOWN COMMAND") ->
                    blockers.add("This wpa_supplicant was built without CONFIG_P2P — Wi-Fi Direct is unavailable.")
                reply.startsWith("FAIL") ->
                    blockers.add(
                        "wpa_supplicant refused p2p_find. Usually the driver isn't P2P-capable, " +
                            "p2p_disabled=1 is set, or Wi-Fi is rfkill'd (check: rfkill list)."
                    )
                reply.isEmpty() ->
                    blockers.add("No reply from wpa_supplicant — is the control socket readable by this user?")
                else -> notes.add("p2p_find replied: $reply")
            }
            wpa("p2p_stop_find")
        }

        val privilege = privilegeMethod()
        if (privilege == null) {
            notes.add(
                "No way to run privileged commands (not root, no passwordless sudo, no pkexec). " +
                    "Group formation may still work, but IP setup will need you to run one command by hand."
            )
        } else {
            notes.add("privileged commands via: $privilege")
        }

        return Readiness(blockers.isEmpty(), blockers, notes)
    }

    // ---- Discovery ---------------------------------------------------------------------------

    /**
     * Start discovery and stream peers as they appear.
     *
     * P2P events arrive on wpa_supplicant's control socket, not from the command we just ran, so we
     * keep an interactive `wpa_cli` alive and read its stdout. Events on the global socket are
     * prefixed `IFNAME=x <3>`; on a per-interface socket just `<3>`. Both are stripped here.
     */
    fun startDiscovery(onPeer: (P2pPeer) -> Unit) {
        startMonitor()
        // The timeout is positional and must come first — `p2p_find type=social 120` silently
        // parses the timeout as 0, which means "never stop".
        val reply = wpa("p2p_find", DISCOVERY_SECONDS.toString(), "type=social")
        if (!reply.equals("OK", ignoreCase = true)) {
            throw P2pException("p2p_find failed: ${reply.ifEmpty { "no reply" }}")
        }
        Thread({
            while (monitor?.isAlive == true) {
                val line = events.poll(500, TimeUnit.MILLISECONDS) ?: continue
                parsePeer(line)?.let { peer ->
                    val fresh = synchronized(peers) { peers.put(peer.deviceAddress, peer) == null }
                    if (fresh) onPeer(peer)
                }
            }
        }, "relaypony-p2p-peers").apply { isDaemon = true; start() }
    }

    /** Peers seen so far, newest last. Also re-reads wpa_supplicant's table in case we missed events. */
    fun knownPeers(): List<P2pPeer> {
        for (mac in wpa("p2p_peers").lines().map { it.trim() }.filter { it.matches(MAC) }) {
            synchronized(peers) {
                if (mac !in peers) peers[mac] = P2pPeer(mac, peerName(mac) ?: mac)
            }
        }
        return synchronized(peers) { peers.values.toList() }
    }

    private fun peerName(mac: String): String? =
        wpa("p2p_peer", mac).lineSequence()
            .firstOrNull { it.startsWith("device_name=") }
            ?.substringAfter('=')?.takeIf { it.isNotBlank() }

    fun stopDiscovery() {
        wpa("p2p_stop_find")
    }

    /**
     * P2P-DEVICE-FOUND <iface addr> p2p_dev_addr=<mac> ... name='<name>' ...
     *
     * The first token is the *interface* address; `p2p_connect` needs the *device* address, which
     * is a different MAC. Getting this wrong produces a connect that never completes.
     */
    private fun parsePeer(line: String): P2pPeer? {
        if (!line.startsWith("P2P-DEVICE-FOUND")) return null
        val dev = Regex("p2p_dev_addr=([0-9a-fA-F:]{17})").find(line)?.groupValues?.get(1) ?: return null
        return P2pPeer(dev, parseQuotedName(line) ?: dev)
    }

    /**
     * Pull `name='…'` out of an event line.
     *
     * The quoting is single quotes with no escaping, and plenty of phones are called things like
     * "Kevin's Pixel" — so a lazy match stops at the apostrophe and silently reports the device as
     * "Kevin". Anchor on the field that always follows instead, and only fall back to lazy matching
     * if the line is shaped differently than expected.
     */
    private fun parseQuotedName(line: String): String? {
        Regex("name='(.*)' config_methods=").find(line)?.let { return it.groupValues[1] }
        return Regex("name='(.*?)'").find(line)?.groupValues?.get(1)
    }

    // ---- Connect -----------------------------------------------------------------------------

    /**
     * Form a group with [deviceAddress] and wait for it to come up.
     *
     * `go_intent=0` on purpose: it hands group ownership to the phone, which then runs its own DHCP
     * server on 192.168.49.1 exactly as it does phone-to-phone. Taking ownership ourselves would
     * mean shipping a DHCP server on the laptop for no gain.
     *
     * `auto` lets wpa_supplicant notice when the phone is already a group owner and join instead of
     * renegotiating. Some Android builds only raise the "Invitation to connect" dialog after a
     * Provision Discovery exchange, so a first attempt that dies quietly is retried with `provdisc`.
     */
    fun connect(
        deviceAddress: String,
        timeoutMs: Long = 90_000,
        onStatus: (String) -> Unit = {},
    ): Group {
        startMonitor()
        prepareForConnect(deviceAddress)
        val perAttempt = (timeoutMs / CONNECT_STRATEGIES.size).coerceAtLeast(25_000)
        var lastProblem = "no response"

        for (modifiers in CONNECT_STRATEGIES) {
            events.clear()
            val reply = wpa("p2p_connect", deviceAddress, *modifiers.toTypedArray())
            note("p2p_connect $deviceAddress ${modifiers.joinToString(" ")} -> $reply")

            if (reply.startsWith("FAIL")) {
                lastProblem = reply
                if (reply.contains("CHANNEL-UNAVAILABLE") || reply.contains("CHANNEL-UNSUPPORTED")) {
                    // Single-radio hardware can't put the group on a different channel than the
                    // network it's already joined to, and no other strategy will change that.
                    throw P2pException(
                        "The radio can't use the channel this group needs ($reply). This usually " +
                            "means it is already joined to a Wi-Fi network on a different channel — " +
                            "disconnect Wi-Fi and try again."
                    )
                }
                continue
            }

            awaitGroup(perAttempt)?.let { return it }
            lastProblem = "connect was accepted but no group formed"
            runCatching { wpa("p2p_cancel") }          // clear the half-started attempt first
        }

        // Last resort, and on Android often the only thing that works: stop initiating and let the
        // phone do it. Android's Wi-Fi Direct UX is built around tapping a device on the phone, and
        // several builds simply never raise the "Invitation to connect" dialog for an inbound
        // request. Pre-authorising the peer means an incoming negotiation is accepted with no
        // prompt on this side, so the whole interaction happens where the user already is.
        onStatus(
            "No response to our invitation. Now waiting for the phone to start it instead — " +
                "on the phone, tap Find devices under Connect directly, then tap this computer."
        )
        awaitInbound(deviceAddress, INBOUND_WAIT_MS)?.let { return it }

        throw P2pException(
            "Could not form a group ($lastProblem). If this machine is joined to a 5 GHz Wi-Fi " +
                "network, disconnect it first — a single-radio adapter cannot run a P2P group on a " +
                "different channel than the one the station is using. On the phone: open RelayPony, tap \"Find " +
                "devices\" under Connect directly so it is actively discovering, tap \"Arm " +
                "receive\" on the Receive tab, keep the screen on, and accept the \"Invitation to " +
                "connect\" prompt when it appears, or tap this computer in the phone's own device " +
                "list. The prompt is shown by Android itself, not by RelayPony, and some builds " +
                "never raise it for an incoming request at all — starting from the phone is the " +
                "reliable direction."
        )
    }

    /**
     * Authorise [deviceAddress] and wait for it to connect to us, rather than the other way around.
     *
     * `p2p_connect … auth` sets up the WPS credentials for that peer without starting negotiation,
     * so when the phone initiates, it completes with no interaction on this side. `p2p_listen` keeps
     * us discoverable in the meantime — without it we are only findable during an active search.
     */
    private fun awaitInbound(deviceAddress: String, timeoutMs: Long): Group? {
        events.clear()
        val reply = wpa("p2p_connect", deviceAddress, "pbc", "auth")
        note("p2p_connect $deviceAddress pbc auth -> $reply")
        if (reply.startsWith("FAIL")) return null
        val listen = wpa("p2p_listen", (timeoutMs / 1000).toString())
        note("p2p_listen -> $listen")
        return awaitGroup(timeoutMs)
    }

    /**
     * Clear whatever the last attempt left behind, and make sure the peer is currently known.
     *
     * Both matter because `p2p_connect` answers a bare `FAIL` for several unrelated reasons. An
     * attempt that timed out mid-connect leaves wpa_supplicant in provisioning state and every later
     * connect is refused until it is cancelled; separately, peer entries age out, and phones stop
     * being discoverable a couple of minutes after the user last tapped "find". Neither is visible
     * in the reply, so we remove both rather than reporting a failure the user can do nothing with.
     */
    private fun prepareForConnect(deviceAddress: String) {
        runCatching { wpa("p2p_cancel") }
        runCatching { wpa("p2p_group_remove", "*") }

        stationFrequency()?.let { mhz ->
            note("station is associated at $mhz MHz")
            if (mhz > 5000) {
                note(
                    "WARNING: the Wi-Fi connection is on 5 GHz. Most single-radio adapters can only " +
                        "put a P2P group on the channel the station is already using, and phones " +
                        "negotiate on 2.4 GHz social channels — so group formation usually fails " +
                        "until Wi-Fi is disconnected."
                )
            }
        }

        val known = wpa("p2p_peers").lines().any { it.trim().equals(deviceAddress, ignoreCase = true) }
        if (!known) {
            note("$deviceAddress not in wpa_supplicant's peer table — refreshing discovery")
            wpa("p2p_find", REFRESH_SECONDS.toString(), "type=social")
            Thread.sleep(REFRESH_WAIT_MS)
        }
    }

    /**
     * The frequency the station interface is associated on, if any.
     *
     * Worth knowing before a connect: a single-radio adapter can't run a P2P group on a different
     * channel from an active station connection, so being joined to a 5 GHz network quietly makes
     * group formation impossible with a phone, which negotiates on 2.4 GHz.
     */
    private fun stationFrequency(): Int? =
        wpa("status").lineSequence()
            .firstOrNull { it.startsWith("freq=") }
            ?.substringAfter('=')?.trim()?.toIntOrNull()

    /**
     * Wait for a group, or for a definitive failure.
     *
     * Returns null rather than throwing when this attempt didn't work, so [connect] can try the
     * next strategy. Only a formed group is success; everything else is worth another approach.
     */
    private fun awaitGroup(timeoutMs: Long): Group? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = events.poll(1000, TimeUnit.MILLISECONDS) ?: continue
            when {
                line.startsWith("P2P-GROUP-STARTED") -> {
                    val g = parseGroupStarted(line) ?: continue
                    group = g
                    note("group up: ${g.iface} ${if (g.isGroupOwner) "GO" else "client"} go_ip=${g.goIpAddr}")
                    return g
                }
                line.startsWith("P2P-GO-NEG-FAILURE") -> {
                    note("go negotiation failed: $line")
                    return null
                }
                line.startsWith("P2P-GROUP-FORMATION-FAILURE") -> {
                    note("group formation failed")
                    return null
                }
            }
        }
        note("timed out after ${timeoutMs / 1000}s with no group")
        return null
    }

    /**
     * P2P-GROUP-STARTED <iface> <GO|client> ssid="…" freq=… [psk=…|passphrase="…"]
     *                   go_dev_addr=<mac> [[PERSISTENT]] [ip_addr=… ip_mask=… go_ip_addr=…]
     *
     * The interface name is always taken from here and never constructed: the index increments
     * across groups, and a long parent name makes wpa_supplicant fall back to plain `p2p-<n>`.
     */
    private fun parseGroupStarted(line: String): Group? {
        val parts = line.split(' ')
        if (parts.size < 3) return null
        return Group(
            iface = parts[1],
            isGroupOwner = parts[2] == "GO",
            goIpAddr = Regex("go_ip_addr=(\\S+)").find(line)?.groupValues?.get(1),
            myIpAddr = Regex("ip_addr=(\\S+)").find(line)?.groupValues?.get(1),
            ipMask = Regex("ip_mask=(\\S+)").find(line)?.groupValues?.get(1),
        )
    }

    // ---- Addressing --------------------------------------------------------------------------

    /**
     * Get an IP on the group interface and work out the peer's address.
     *
     * wpa_supplicant deliberately does no L3 configuration, so this is on us. In order of
     * preference: the addresses the peer handed us inside the EAPOL handshake (no DHCP round trip
     * at all), then a DHCP client, then Android's documented group-owner address as a last resort.
     */
    fun resolvePeerAddress(g: Group): String {
        if (g.isGroupOwner) {
            // We own the group, so the phone will DHCP against us and we need a server for it.
            val assigned = ANDROID_GO_ADDRESS
            runPrivileged(listOf("ip", "addr", "add", "$assigned/24", "dev", g.iface))
            runPrivileged(listOf("ip", "link", "set", g.iface, "up"))
            if (which("dnsmasq") == null) {
                throw P2pException(
                    "This machine became the group owner, which means it has to hand the phone an " +
                        "address, and dnsmasq isn't installed. Install dnsmasq, or retry — " +
                        "RelayPony asks for go_intent=0 so the phone normally owns the group."
                )
            }
            throw P2pException(
                "This machine became the group owner. That path needs a DHCP server running on " +
                    "${g.iface} and is not wired up yet; retry so the phone takes ownership."
            )
        }

        // Client side. Best case: the peer allocated us an address during the 4-way handshake.
        if (g.myIpAddr != null && g.goIpAddr != null) {
            val prefix = maskToPrefix(g.ipMask) ?: 24
            note("using EAPOL-allocated ${g.myIpAddr}/$prefix, peer ${g.goIpAddr}")
            runPrivileged(listOf("ip", "addr", "add", "${g.myIpAddr}/$prefix", "dev", g.iface))
            runPrivileged(listOf("ip", "link", "set", g.iface, "up"))
            if (waitForAddress(g.iface)) return g.goIpAddr
        }

        val dhcp = listOf(
            listOf("dhclient", "-1", g.iface),
            listOf("dhcpcd", "-1", g.iface),
            listOf("udhcpc", "-n", "-q", "-i", g.iface),
        ).firstOrNull { which(it.first()) != null }

        if (dhcp != null) {
            note("running ${dhcp.first()} on ${g.iface}")
            runPrivileged(dhcp)
            if (waitForAddress(g.iface)) {
                gatewayOn(g.iface)?.let { return it }
            }
        } else {
            note("no DHCP client found (tried dhclient, dhcpcd, udhcpc)")
        }

        // Android's group owner is documented as 192.168.49.1/24 and has been for many releases.
        // If we still have no address, self-assign inside that subnet and try it.
        if (!hasAddress(g.iface)) {
            note("falling back to Android's documented group-owner subnet")
            runPrivileged(listOf("ip", "addr", "add", "$FALLBACK_CLIENT_ADDRESS/24", "dev", g.iface))
            runPrivileged(listOf("ip", "link", "set", g.iface, "up"))
        }
        if (!hasAddress(g.iface)) {
            throw P2pException(
                "The group formed on ${g.iface} but it has no IP address, and nothing here could " +
                    "give it one. Run this in another terminal and try again:\n" +
                    "  sudo dhclient -1 ${g.iface}\n" +
                    "or, if the phone is the group owner:\n" +
                    "  sudo ip addr add $FALLBACK_CLIENT_ADDRESS/24 dev ${g.iface}"
            )
        }
        return gatewayOn(g.iface) ?: g.goIpAddr ?: ANDROID_GO_ADDRESS
    }

    private fun waitForAddress(iface: String, timeoutMs: Long = 8000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasAddress(iface)) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun hasAddress(iface: String): Boolean =
        runCatching { java.net.NetworkInterface.getByName(iface) }.getOrNull()
            ?.interfaceAddresses?.any { it.address is java.net.Inet4Address } == true

    /** The gateway learned for [iface] — on a P2P link that is the group owner, i.e. the phone. */
    private fun gatewayOn(iface: String): String? {
        val out = exec(listOf("ip", "-4", "route", "show", "dev", iface)).output
        Regex("default via (\\S+)").find(out)?.let { return it.groupValues[1] }
        // No default route on the group interface is normal; derive the GO from the subnet instead.
        Regex("(\\d+\\.\\d+\\.\\d+)\\.\\d+/(\\d+)").find(out)?.let { return "${it.groupValues[1]}.1" }
        return null
    }

    private fun maskToPrefix(mask: String?): Int? {
        val parts = mask?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return null
        if (parts.size != 4) return null
        return parts.sumOf { Integer.bitCount(it) }
    }

    // ---- Teardown ----------------------------------------------------------------------------

    /** Undo everything, in the order wpa_supplicant expects. Each step is safe to fail. */
    fun disconnect() {
        wpa("p2p_cancel")                       // aborts a formation still in progress
        wpa("p2p_group_remove", "*")            // wildcard: no need to have tracked the iface name
        wpa("p2p_stop_find")
        group = null
    }

    fun close() {
        runCatching { disconnect() }
        monitor?.let { runCatching { it.destroy() } }
        monitor = null
    }

    // ---- wpa_cli plumbing --------------------------------------------------------------------

    /**
     * Keep an interactive `wpa_cli` alive purely to receive unsolicited events. `wpa_cli -a` only
     * fires its script for five of the P2P events — not DEVICE-FOUND, not GO-NEG-SUCCESS — so it
     * cannot drive this, and reading the interactive stream is the supported alternative.
     */
    @Synchronized
    private fun startMonitor() {
        if (monitor?.isAlive == true) return
        val cli = which("wpa_cli") ?: throw P2pException("wpa_cli not found on PATH.")
        if (socketArg.isEmpty()) socketArg = findControlSocket()
            ?: throw P2pException("No wpa_supplicant control socket found.")
        val proc = ProcessBuilder(listOf(cli) + socketArg)
            .redirectErrorStream(true)
            .start()
        monitor = proc
        Thread({
            // runCatching, because closing the monitor destroys the process out from under this
            // read and the resulting "Stream closed" is expected teardown, not an error worth
            // printing on top of whatever the user was actually told.
            runCatching {
                proc.inputStream.bufferedReader().use { reader: BufferedReader ->
                    while (true) {
                        val raw = reader.readLine() ?: break
                        val line = stripEventPrefix(raw)
                        if (line.startsWith("P2P") || line.startsWith("AP-STA")) {
                            note("event: $line")
                            events.offer(line)
                        }
                    }
                }
            }
        }, "relaypony-p2p-events").apply { isDaemon = true; start() }
    }

    /** Events arrive as `<3>TEXT`, or `IFNAME=wlan0 <3>TEXT` on the global socket. */
    private fun stripEventPrefix(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("IFNAME=")) s = s.substringAfter(' ', s)
        if (s.length > 2 && s[0] == '<' && s[2] == '>') s = s.substring(3)
        return s.trim()
    }

    /** Run one wpa_cli command and return its reply text. The exit code is never meaningful. */
    private fun wpa(vararg command: String): String {
        val cli = which("wpa_cli") ?: return ""
        if (socketArg.isEmpty()) socketArg = findControlSocket() ?: return ""
        val result = exec(listOf(cli) + socketArg + command.toList())
        // wpa_cli echoes connection banners before the reply; the reply is the meaningful tail.
        return result.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Selected interface") && !it.startsWith("<") }
            .joinToString("\n")
            .trim()
    }

    /**
     * Prefer the global control socket: it routes every P2P command to the right interface by
     * itself, which sidesteps the classic trap that events appear on `p2p-dev-wlan0` while commands
     * are being sent to `wlan0`.
     */
    private fun controlDirs(): List<File> =
        listOf("/run/wpa_supplicant", "/var/run/wpa_supplicant").map(::File).filter { it.isDirectory }

    /**
     * Say precisely why there is no usable control socket, because the two causes need opposite
     * fixes and guessing wrong wastes real time.
     *
     * The directory being absent means wpa_supplicant was never told to create a socket, which is a
     * systemd unit edit. The directory being present but unreadable means it did create one and this
     * user simply is not in the group that owns it (Debian and Ubuntu ship `GROUP=netdev` already
     * configured), which is one `usermod` away. Reporting the first when it is really the second
     * sends someone rewriting service files for a permissions problem.
     */
    private fun controlSocketBlocker(): String {
        val dirs = controlDirs()
        if (dirs.isEmpty()) {
            return "No wpa_supplicant control socket directory (/run/wpa_supplicant or " +
                "/var/run/wpa_supplicant). If NetworkManager runs wpa_supplicant with only -u " +
                "(D-Bus), no socket is created; add '-O /run/wpa_supplicant' to the service's " +
                "ExecStart, then restart wpa_supplicant and NetworkManager."
        }
        val unreadable = dirs.filterNot { it.canRead() }
        if (unreadable.size == dirs.size) {
            val dir = unreadable.first()
            return "${dir.path} exists but this user cannot read it — wpa_supplicant is creating " +
                "the socket, this account just has no access to it. The directory is normally owned " +
                "by the 'netdev' group: run 'sudo usermod -aG netdev \$USER', then log out and back " +
                "in. (Check with: ls -ld ${dir.path})"
        }
        val dir = dirs.first { it.canRead() }
        return "${dir.path} is readable but contains no interface socket. Is Wi-Fi enabled and " +
            "wpa_supplicant running? (check: ls -la ${dir.path} ; systemctl status wpa_supplicant)"
    }

    private fun findControlSocket(): List<String>? {
        // Only readable directories are candidates: listFiles() on an unreadable one returns null,
        // which would otherwise look identical to an empty directory.
        val dirs = controlDirs().filter { it.canRead() }
        for (dir in dirs) {
            File(dir, "global").takeIf { it.exists() }?.let { return listOf("-g", it.path) }
        }
        for (dir in dirs) {
            val entries = dir.listFiles()?.map { it.name }.orEmpty()
            entries.firstOrNull { it.startsWith("p2p-dev-") }?.let { return listOf("-p", dir.path, "-i", it) }
            entries.firstOrNull { isWireless(it) }?.let { return listOf("-p", dir.path, "-i", it) }
            entries.firstOrNull()?.let { return listOf("-p", dir.path, "-i", it) }
        }
        return null
    }

    private fun isWireless(iface: String): Boolean = File("/sys/class/net/$iface/wireless").exists() ||
        File("/sys/class/net/$iface/phy80211").exists()

    /** Interface modes from `iw list`, or null when `iw` isn't installed. */
    private fun supportedInterfaceModes(): List<String>? {
        if (which("iw") == null) return null
        val out = exec(listOf("iw", "list")).output
        if (out.isBlank()) return null
        val modes = ArrayList<String>()
        var inBlock = false
        for (line in out.lines()) {
            if (line.contains("Supported interface modes:")) { inBlock = true; continue }
            if (!inBlock) continue
            val trimmed = line.trim()
            if (trimmed.startsWith("* ")) modes.add(trimmed.removePrefix("* ").trim()) else inBlock = false
        }
        return modes
    }

    // ---- Process helpers ---------------------------------------------------------------------

    private data class ExecResult(val code: Int, val output: String)

    private fun exec(command: List<String>, timeoutMs: Long = 15_000): ExecResult = runCatching {
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
        ExecResult(runCatching { proc.exitValue() }.getOrDefault(-1), text)
    }.getOrElse { ExecResult(-1, "") }

    /** How we can gain root, if at all: null means we cannot and the caller must say so. */
    private fun privilegeMethod(): String? = when {
        System.getProperty("user.name") == "root" -> "already root"
        which("sudo") != null && exec(listOf("sudo", "-n", "true")).code == 0 -> "sudo -n"
        which("pkexec") != null -> "pkexec"
        else -> null
    }

    /** Best-effort privileged command. Failure is recorded, not thrown — the caller checks results. */
    private fun runPrivileged(command: List<String>) {
        val prefixed = when (privilegeMethod()) {
            "already root" -> command
            "sudo -n" -> listOf("sudo", "-n") + command
            "pkexec" -> listOf("pkexec") + command
            else -> {
                note("cannot run (no privilege): ${command.joinToString(" ")}")
                return
            }
        }
        val r = exec(prefixed)
        note("${prefixed.joinToString(" ")} -> exit ${r.code}${if (r.output.isBlank()) "" else ": " + r.output.trim().lines().first()}")
    }

    private fun which(binary: String): String? {
        val path = System.getenv("PATH")?.split(File.pathSeparatorChar).orEmpty() +
            listOf("/usr/sbin", "/sbin", "/usr/local/sbin")   // wpa_cli and ip often live here
        for (dir in path) {
            val f = File(dir, binary)
            if (f.isFile && f.canExecute()) return f.path
        }
        return null
    }

    private fun isLinux(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    private companion object {
        val MAC = Regex("^[0-9a-fA-F:]{17}$")
        const val DISCOVERY_SECONDS = 120
        const val REFRESH_SECONDS = 15
        const val REFRESH_WAIT_MS = 6_000L
        const val INBOUND_WAIT_MS = 60_000L

        /**
         * Connection strategies, tried in order until one forms a group.
         *
         * `provdisc` leads deliberately. It asks for a Provision Discovery exchange before GO
         * negotiation, which is what makes Android raise its "Invitation to connect" dialog — and
         * without that dialog the user has no way to accept, so the attempt times out having looked
         * like it was accepted. wpa_supplicant documents it as a workaround for exactly this class
         * of deployed implementation.
         *
         * `auto` then covers the case where the phone is already a group owner (it joins instead of
         * renegotiating), and `join` forces that reading if `auto` guessed wrong.
         *
         * `go_intent=0` throughout: let the phone own the group, so it runs the DHCP server it
         * already runs for phone-to-phone transfers.
         */
        val CONNECT_STRATEGIES = listOf(
            listOf("pbc", "go_intent=0", "provdisc"),
            listOf("pbc", "go_intent=0", "auto"),
            listOf("pbc", "go_intent=0", "join"),
        )
        const val MAX_LOG = 200

        /** Android's group owner address, stable across releases (WifiP2pServiceImpl). */
        const val ANDROID_GO_ADDRESS = "192.168.49.1"

        /** Inside Android's DHCP pool but below the EAPOL reservation that starts at .128. */
        const val FALLBACK_CLIENT_ADDRESS = "192.168.49.20"
    }
}
