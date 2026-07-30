package com.relaypony.desktop

import com.relaypony.transport.LocalInterfaces
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The last resort: knock on every door in the subnet and see which ones answer on our port.
 *
 * This is the backstop behind mDNS and the UDP beacon, for a peer that answers neither — an older
 * build, a machine where broadcast is filtered but unicast isn't, a firewall that blocks 5353 while
 * leaving the transfer port open. It only works against peers on a *stable* port, which is why the
 * fixed default port ([Config.DEFAULT_PORT]) and the sweep arrived together.
 *
 * What a hit means and doesn't. A successful TCP connect proves something is listening; it proves
 * nothing about who. There is no identity here — the address is a candidate, and the security model
 * is unchanged because we still encrypt to a pinned handle. Point a send at the wrong address and
 * it fails cleanly rather than leaking anything.
 *
 * Bounded on purpose: subnets wider than a /24 are skipped rather than turned into 65,000 connects.
 * The beacon is the mechanism for large networks.
 */
object SubnetSweep {

    data class Hit(val host: String, val port: Int, val via: String, val isGateway: Boolean)

    /**
     * Addresses worth trying, most-likely first. The default gateway leads: when a phone is sharing
     * its hotspot the gateway *is* the phone, so it is both the likeliest peer and one probe.
     */
    fun candidates(maxHostsPerSubnet: Int = 256): List<Pair<InetAddress, String>> {
        val endpoints = LocalInterfaces.endpoints()
        val gateways = LocalInterfaces.defaultGateways()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Pair<InetAddress, String>>()

        fun add(addr: InetAddress, via: String) {
            val key = addr.hostAddress ?: return
            if (seen.add(key)) out.add(addr to via)
        }

        for (gw in gateways) {
            val via = endpoints.firstOrNull { LocalInterfaces.sameSubnet(gw, it) }?.ifaceName ?: "gateway"
            add(gw, via)
        }
        // .1 of each subnet: the near-universal router/hotspot address, and free to try even when
        // /proc/net/route was unavailable (macOS, Windows).
        for (e in endpoints) {
            LocalInterfaces.hostsIn(e, maxHostsPerSubnet).firstOrNull()?.let { add(it, e.ifaceName) }
        }
        for (e in endpoints) {
            for (host in LocalInterfaces.hostsIn(e, maxHostsPerSubnet)) add(host, e.ifaceName)
        }
        return out
    }

    /**
     * Probe [port] across [candidates] in parallel and report what answered.
     *
     * [onHit] fires as results arrive so a UI can fill in progressively. The whole sweep is capped
     * by [timeoutMs] per host; with the default concurrency a /24 completes in a couple of seconds.
     */
    fun run(
        port: Int,
        timeoutMs: Int = 400,
        concurrency: Int = 64,
        maxHostsPerSubnet: Int = 256,
        onHit: (Hit) -> Unit = {},
    ): List<Hit> {
        val targets = candidates(maxHostsPerSubnet)
        if (targets.isEmpty()) return emptyList()
        val gatewayKeys = LocalInterfaces.defaultGateways().mapNotNull { it.hostAddress }.toSet()

        val pool = Executors.newFixedThreadPool(concurrency.coerceIn(1, 128)) { r ->
            Thread(r, "relaypony-sweep").apply { isDaemon = true }
        }
        return try {
            val tasks = targets.map { (addr, via) ->
                Callable {
                    val host = addr.hostAddress ?: return@Callable null
                    val open = runCatching {
                        Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) }
                        true
                    }.getOrDefault(false)
                    if (!open) return@Callable null
                    Hit(host, port, via, host in gatewayKeys).also(onHit)
                }
            }
            // A generous ceiling, not the expected duration: tasks run concurrently and most of them
            // time out at once. Without it a black-holed subnet could hang the caller indefinitely.
            val budget = (timeoutMs.toLong() * targets.size / concurrency.coerceAtLeast(1)) + 5_000
            pool.invokeAll(tasks, budget, TimeUnit.MILLISECONDS)
                .mapNotNull { f -> runCatching { if (f.isCancelled) null else f.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }
}
