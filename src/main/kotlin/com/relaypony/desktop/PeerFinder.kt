package com.relaypony.desktop

import com.relaypony.transport.Beacon
import com.relaypony.transport.BeaconDiscovery
import com.relaypony.transport.LocalInterfaces
import com.relaypony.transport.WireProtocol

/**
 * One place that answers "who is out there", across every mechanism we have.
 *
 * Three of them, in order of how much they tell you:
 *
 *  1. **mDNS** — the nice path. Rich, standard, interoperable, and the first thing a network drops.
 *  2. **The UDP beacon** — broadcast rather than multicast, and sent from a socket pinned to each
 *     local interface. This is what crosses a phone hotspot, where mDNS cannot. Same three facts.
 *  3. **The subnet sweep** — TCP-knocks the local /24. No identity, just "something is listening
 *     on the RelayPony port at this address", offered to the user as a candidate.
 *
 * They run together rather than as a fallback chain: a peer that answers two of them is simply
 * deduplicated by handle, and the first mechanism to produce a usable address wins. Nothing here
 * changes the security model — a peer is still only sendable if its handle is pinned, and files are
 * still encrypted to that pinned key regardless of which mechanism produced the address.
 */
class PeerFinder(private val selfHandle: String? = null) {

    private val mdns = DesktopDiscovery()
    private val beacon = BeaconDiscovery()
    private var listening = false

    /** Everything we know about how discovery is going, for `doctor` and the Receive screen. */
    data class Diagnostics(
        val mdnsBound: List<String>,
        val skipped: List<LocalInterfaces.Skipped>,
        val problems: List<String>,
    )

    /**
     * Begin continuous discovery: browse mDNS and listen for beacon announcements. [onPeer] fires on
     * background threads and may repeat for a peer; callers deduplicate by handle.
     */
    fun start(onPeer: (DesktopDiscovery.Peer) -> Unit) {
        mdns.browse { onPeer(it) }
        if (!listening) {
            listening = true
            beacon.listen(selfHandle) { onPeer(it.toPeer()) }
        }
    }

    /**
     * One active search round: probe the beacon and wait out an mDNS browse. Returns everything
     * found, deduplicated by handle, mDNS entries preferred because they carry a resolved hostname.
     */
    fun findOnce(timeoutMs: Long = 4000): List<DesktopDiscovery.Peer> {
        val found = LinkedHashMap<String, DesktopDiscovery.Peer>()
        val collect: (DesktopDiscovery.Peer) -> Unit = { p ->
            synchronized(found) {
                val existing = found[p.recipientHandle]
                // Prefer whichever source we heard first, except that a beacon hit with a same-subnet
                // address beats an mDNS entry we can't reach.
                if (existing == null) found[p.recipientHandle] = p
            }
        }
        mdns.browse(collect)
        beacon.listen(selfHandle) { collect(it.toPeer()) }
        listening = true
        beacon.probe(timeoutMs.coerceAtMost(2500)) { collect(it.toPeer()) }
        val remaining = timeoutMs - 2500
        if (remaining > 0) Thread.sleep(remaining)
        return synchronized(found) { found.values.toList() }
    }

    /**
     * The last-resort scan. Returns addresses that answered on [port] and are not already accounted
     * for by [known] — no identity attached, so callers must present them as candidates.
     */
    fun sweep(port: Int, known: Collection<DesktopDiscovery.Peer> = emptyList()): List<SubnetSweep.Hit> {
        val seen = known.map { it.host }.toSet()
        return SubnetSweep.run(port).filterNot { it.host in seen }
    }

    /** Announce ourselves on every mechanism. Returns the mDNS interfaces that accepted us. */
    fun advertise(tcpPort: Int, deviceName: String, recipientHandle: String): List<String> {
        beacon.advertise(tcpPort, deviceName, recipientHandle, WireProtocol.MAX_WIRE_VERSION)
        return mdns.advertise("RelayPony-$tcpPort", tcpPort, deviceName, recipientHandle)
    }

    fun stopAdvertising() {
        beacon.stopAdvertising()
        mdns.stopAdvertising()
    }

    fun diagnostics(): Diagnostics {
        val d = mdns.diagnostics()
        return Diagnostics(
            mdnsBound = d.bound,
            skipped = d.skipped,
            problems = d.problems + beacon.problems.toList(),
        )
    }

    fun close() {
        runCatching { beacon.close() }
        runCatching { mdns.close() }
        listening = false
    }

    private fun BeaconDiscovery.Peer.toPeer() = DesktopDiscovery.Peer(
        name = name,
        host = host,
        port = port,
        recipientHandle = recipientHandle,
        maxWire = maxWire,
        source = "beacon",
        via = via,
    )

    companion object {
        /** The UDP port the beacon uses, surfaced so firewall advice can name it. */
        const val BEACON_PORT = Beacon.PORT
    }
}
