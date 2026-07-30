package com.relaypony.desktop

import com.relaypony.transport.LocalInterfaces
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Desktop-flavoured networking helpers on top of the shared [LocalInterfaces].
 *
 * The interface enumeration itself lives in `:transport` because the phones need exactly the same
 * logic and the two must not be allowed to drift — a discovery bug that exists on one platform and
 * not the other is the hardest kind to reason about. What stays here is only what the desktop
 * needs: opening the listening socket, probing a peer, and phrasing an address for a human.
 */
object LocalNet {

    /**
     * Restrict binding to a comma-separated interface allowlist, e.g. `RELAYPONY_IFACE=wlan0`.
     * Useful on a box with many virtual bridges, or to force traffic onto one link.
     */
    private fun allowlist(): Set<String>? =
        System.getenv("RELAYPONY_IFACE")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.toSet()

    /** Every up, non-loopback IPv4 endpoint on this machine. */
    fun endpoints(): List<LocalInterfaces.Endpoint> = LocalInterfaces.endpoints(allowlist())

    /** The subset that can carry multicast — the only ones mDNS can use. */
    fun multicastEndpoints(): List<LocalInterfaces.Endpoint> = endpoints().filter { it.supportsMulticast }

    /** Interfaces we walked past, with the reason — so a failure to appear is explainable. */
    fun skipped(): List<LocalInterfaces.Skipped> {
        val base = LocalInterfaces.skipped(allowlist()).toMutableList()
        // mDNS has a requirement the beacon doesn't, so call it out separately rather than
        // hiding a working interface behind a blanket "skipped".
        endpoints().filterNot { it.supportsMulticast }.forEach {
            base.add(LocalInterfaces.Skipped(it.ifaceName, "no multicast (beacon still uses it)"))
        }
        return base
    }

    fun sameSubnet(peer: java.net.InetAddress, endpoint: LocalInterfaces.Endpoint): Boolean =
        LocalInterfaces.sameSubnet(peer, endpoint)

    /**
     * Bind a listening socket, preferring [preferred] so the address is predictable (firewall rules,
     * manual `--host`/`--port` sends, and the subnet sweep, which can only find a stable port).
     * Falls back to an OS-assigned port if it is already taken, because refusing to start would be
     * worse than being harder to find.
     */
    fun listen(preferred: Int): ServerSocket = when {
        preferred <= 0 -> ServerSocket(0)
        else -> runCatching { ServerSocket(preferred) }.getOrElse { ServerSocket(0) }
    }

    /** Is anything answering on [host]:[port]? Used by `doctor` to sanity-check a discovered peer. */
    fun probe(host: String, port: Int, timeoutMs: Int = 1500): Boolean = runCatching {
        java.net.Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)

    /** Human-readable "where to reach me" lines for the Receive screen and the CLI banner. */
    fun reachableAt(port: Int): List<String> = endpoints().map { "${it.ip}:$port  (${it.ifaceName})" }

    /** Address-only view, for callers that just need "where am I". */
    fun addresses(): List<Inet4Address> = endpoints().map { it.address }
}
