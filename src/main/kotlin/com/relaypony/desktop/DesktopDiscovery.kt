package com.relaypony.desktop

import com.relaypony.transport.WireProtocol
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Desktop mDNS discovery over `_relaypony._tcp`, the JVM counterpart of the Android NsdDiscovery.
 * Uses jmdns (standard DNS-SD), so it interoperates with the phones' and the Mac app's Bonjour/NSD.
 * Carries the same three TXT attributes the family uses: name, rcpt (the age1 handle), and mw.
 *
 * Addressing: we bind jmdns to this host's LAN IPv4 and prefer a peer's IPv4 for the connect path.
 * If a peer only resolves to a link-local IPv6 (fe80::…, common on macOS where jmdns shares port
 * 5353 with the system mDNSResponder), we attach the LAN interface's scope id so it is routable.
 */
class DesktopDiscovery {

    data class Peer(
        val name: String,
        val host: String,
        val port: Int,
        val recipientHandle: String,
        val maxWire: Int = 1,
    )

    private var jmdns: JmDNS? = null

    @Synchronized
    private fun instance(): JmDNS = jmdns ?: run {
        val jm = localLanAddress()?.let { JmDNS.create(it) } ?: JmDNS.create()
        jm.also { jmdns = it }
    }

    fun advertise(instanceName: String, port: Int, deviceName: String, recipientHandle: String) {
        val props = mapOf(
            "name" to deviceName,
            "rcpt" to recipientHandle,
            "mw" to WireProtocol.MAX_WIRE_VERSION.toString(),
        )
        instance().registerService(ServiceInfo.create(SERVICE_TYPE, instanceName, port, 0, 0, props))
    }

    fun browse(onPeer: (Peer) -> Unit) {
        val jm = instance()
        jm.addServiceListener(SERVICE_TYPE, object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jm.requestServiceInfo(event.type, event.name, 2000)
            }
            override fun serviceRemoved(event: ServiceEvent) {}
            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val rcpt = info.getPropertyString("rcpt") ?: return
                val host = bestHost(info) ?: return
                val name = info.getPropertyString("name") ?: event.name
                val mw = info.getPropertyString("mw")?.toIntOrNull()?.let { if (it >= 1) it else 1 } ?: 1
                onPeer(Peer(name, host, info.port, rcpt, mw))
            }
        })
    }

    /** Stop advertising our service but keep the jmdns instance alive (e.g. browsing continues). */
    fun stopAdvertising() {
        runCatching { jmdns?.unregisterAllServices() }
    }

    fun close() {
        runCatching { jmdns?.close() }
        jmdns = null
    }

    /** Pick a routable host string for a resolved peer: IPv4 if available, else a scope-attached
     *  link-local IPv6, else whatever address we have. */
    private fun bestHost(info: ServiceInfo): String? {
        info.inet4Addresses.firstOrNull()?.let { return it.hostAddress }
        val v6 = info.inet6Addresses.firstOrNull() ?: info.inetAddresses.firstOrNull() ?: return null
        if (v6 is Inet6Address && v6.isLinkLocalAddress && v6.scopeId == 0) {
            localLanInterface()?.let { nif ->
                runCatching { Inet6Address.getByAddress(null, v6.address, nif) }
                    .getOrNull()?.let { return it.hostAddress }   // includes %en0
            }
        }
        return v6.hostAddress
    }

    /** The up, non-loopback interface carrying a private IPv4 — our path onto the LAN. */
    private fun localLanInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .firstOrNull { nif ->
                nif.inetAddresses.asSequence().filterIsInstance<Inet4Address>().any { it.isSiteLocalAddress }
            }
    }.getOrNull()

    private fun localLanAddress(): InetAddress? =
        localLanInterface()?.inetAddresses?.asSequence()
            ?.filterIsInstance<Inet4Address>()?.firstOrNull { it.isSiteLocalAddress }

    companion object {
        const val SERVICE_TYPE = "_relaypony._tcp.local."
    }
}
