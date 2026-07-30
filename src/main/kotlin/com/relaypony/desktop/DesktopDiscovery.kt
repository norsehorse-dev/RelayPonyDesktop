package com.relaypony.desktop

import com.relaypony.transport.LocalInterfaces
import com.relaypony.transport.WireProtocol
import java.net.Inet4Address
import java.net.Inet6Address
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Desktop mDNS discovery over `_relaypony._tcp`, the JVM counterpart of the Android NsdDiscovery.
 * Uses jmdns (standard DNS-SD), so it interoperates with the phones' and the Mac app's Bonjour/NSD.
 * Carries the same three TXT attributes the family uses: name, rcpt (the age1 handle), and mw.
 *
 * Multi-homed by construction. A jmdns instance speaks on exactly one bound address, so we create
 * one per usable IPv4 endpoint ([LocalNet.endpoints]) and advertise/browse on all of them at once.
 * The previous single-instance version bound to the first RFC1918 address it found, which on a
 * laptop with ethernet, `docker0`, `virbr0` or a VPN up was frequently the wrong link — and when it
 * found nothing site-local it fell back to `JmDNS.create()`, i.e. `InetAddress.getLocalHost()`,
 * which on Arch/Debian resolves to 127.0.1.1 and confines the advertisement to loopback. Both
 * failure modes were silent. Neither can happen now, and [diagnostics] explains what was bound.
 *
 * Addressing: we prefer a peer address that shares a subnet with one of ours, so a peer that
 * advertises several IPv4s (VPN, second NIC) is contacted on the link we can actually reach it on.
 * A link-local IPv6 (fe80::…, common on macOS where jmdns shares port 5353 with mDNSResponder)
 * gets the scope id of the interface it was heard on so it stays routable.
 */
class DesktopDiscovery {

    data class Peer(
        val name: String,
        val host: String,
        val port: Int,
        val recipientHandle: String,
        val maxWire: Int = 1,
        /** How we heard about this peer: "mDNS", "beacon", or "address". Shown in diagnostics. */
        val source: String = "mDNS",
        /** The interface it was heard on, where we know it. */
        val via: String = "",
    )

    /** What we bound, what we skipped, and anything that went wrong doing it. */
    data class Diagnostics(
        val bound: List<String>,
        val skipped: List<LocalInterfaces.Skipped>,
        val problems: List<String>,
    )

    private class Bound(val endpoint: LocalInterfaces.Endpoint, val jmdns: JmDNS)

    private val problems = ArrayList<String>()
    private var bound: List<Bound>? = null
    private var closed = false

    @Synchronized
    private fun instances(): List<Bound> {
        bound?.let { return it }
        if (closed) return emptyList()
        val made = ArrayList<Bound>()
        for (endpoint in LocalNet.multicastEndpoints()) {
            runCatching { JmDNS.create(endpoint.address) }
                .onSuccess { made.add(Bound(endpoint, it)) }
                .onFailure { problems.add("mDNS bind failed on ${endpoint.ifaceName} (${endpoint.ip}): ${reason(it)}") }
        }
        if (made.isEmpty() && problems.isEmpty()) {
            problems.add("No usable network interface found — is Wi-Fi or ethernet connected?")
        }
        bound = made
        return made
    }

    /** Interfaces currently carrying our advertisement/browse, plus why anything else was skipped. */
    @Synchronized
    fun diagnostics(): Diagnostics = Diagnostics(
        bound = instances().map { it.endpoint.toString() },
        skipped = LocalNet.skipped(),
        problems = problems.toList(),
    )

    /**
     * Announce this device on every bound interface. Returns the interfaces the registration
     * actually succeeded on — an empty list means nobody can discover us, which callers should say
     * out loud rather than swallow.
     */
    fun advertise(instanceName: String, port: Int, deviceName: String, recipientHandle: String): List<String> {
        val props = mapOf(
            "name" to deviceName,
            "rcpt" to recipientHandle,
            "mw" to WireProtocol.MAX_WIRE_VERSION.toString(),
        )
        val ok = ArrayList<String>()
        for (b in instances()) {
            // A ServiceInfo is stateful and gets bound to the JmDNS that registers it, so each
            // instance needs its own copy. Sharing one silently registers on a single interface.
            val info = ServiceInfo.create(SERVICE_TYPE, instanceName, port, 0, 0, props)
            runCatching { b.jmdns.registerService(info) }
                .onSuccess { ok.add(b.endpoint.ifaceName) }
                .onFailure { problems.add("Advertise failed on ${b.endpoint.ifaceName}: ${reason(it)}") }
        }
        return ok
    }

    fun browse(onPeer: (Peer) -> Unit) {
        for (b in instances()) {
            val jm = b.jmdns
            val endpoint = b.endpoint
            runCatching {
                jm.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        runCatching { jm.requestServiceInfo(event.type, event.name, 2000) }
                    }

                    override fun serviceRemoved(event: ServiceEvent) {}

                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info ?: return
                        val rcpt = info.getPropertyString("rcpt") ?: return
                        val host = bestHost(info, endpoint) ?: return
                        val name = info.getPropertyString("name") ?: event.name
                        val mw = info.getPropertyString("mw")?.toIntOrNull()?.let { if (it >= 1) it else 1 } ?: 1
                        onPeer(Peer(name, host, info.port, rcpt, mw))
                    }
                })
            }.onFailure { problems.add("Browse failed on ${endpoint.ifaceName}: ${reason(it)}") }
        }
    }

    /** Stop advertising but keep the jmdns instances alive (e.g. browsing continues). */
    @Synchronized
    fun stopAdvertising() {
        bound?.forEach { runCatching { it.jmdns.unregisterAllServices() } }
    }

    @Synchronized
    fun close() {
        bound?.forEach { runCatching { it.jmdns.close() } }
        bound = null
        closed = true
    }

    /**
     * Pick a routable host for a resolved peer.
     *
     * Order: an IPv4 on the same subnet as the interface we heard it on → an IPv4 on any subnet of
     * ours → any IPv4 → a link-local IPv6 with the hearing interface's scope attached.
     */
    private fun bestHost(info: ServiceInfo, heardOn: LocalInterfaces.Endpoint): String? {
        val v4 = runCatching { info.inet4Addresses.filterNotNull() }.getOrDefault(emptyList())
        if (v4.isNotEmpty()) {
            v4.firstOrNull { peer -> LocalNet.sameSubnet(peer, heardOn) }?.let { return it.hostAddress }
            val ours = LocalNet.endpoints()
            v4.firstOrNull { peer -> ours.any { LocalNet.sameSubnet(peer, it) } }?.let { return it.hostAddress }
            return v4.first().hostAddress
        }
        val v6 = runCatching { info.inet6Addresses.firstOrNull() ?: info.inetAddresses.firstOrNull() }
            .getOrNull() ?: return null
        if (v6 is Inet6Address && v6.isLinkLocalAddress && v6.scopeId == 0) {
            runCatching { Inet6Address.getByAddress(null, v6.address, heardOn.nif) }
                .getOrNull()?.let { return it.hostAddress }   // includes %wlan0
        }
        return v6.hostAddress
    }

    private fun reason(t: Throwable): String = t.message ?: t.javaClass.simpleName

    companion object {
        const val SERVICE_TYPE = "_relaypony._tcp.local."

        /** Address-only view, for callers that need "where am I" without opening a jmdns instance. */
        fun localAddresses(): List<Inet4Address> = LocalNet.addresses()
    }
}
