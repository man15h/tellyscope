// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import com.flyfishxu.kadb.mdns.KadbMdns
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A device answering on the LAN, before any ADB handshake. */
data class ScanHit(
    val host: String,
    val port: Int,
    val kind: Kind,
    /** mDNS service name, else reverse DNS; null when neither answers. */
    val name: String?,
) {
    enum class Kind {
        /** `adb tcpip` / network debugging on a fixed port (usually 5555). */
        CLASSIC,
        /** Android 11+ wireless debugging, already paired: connect port from `_adb-tls-connect`. */
        WIRELESS,
        /** Wireless debugging with the "pair with code" box open: needs the 6-digit code. */
        PAIRING,
    }
}

/**
 * Finds ADB on the LAN: a TCP sweep of [port] over the hosts the caller passes, plus, if given, mDNS
 * for wireless debugging. The caller picks the network: only Wi-Fi or Ethernet, never cellular or a
 * VPN (see [subnetHosts], [lanHosts]). MAC/maker lookup is left out: Android 10+ apps can't read
 * the ARP table.
 */
class LanScanner(
    private val mdns: KadbMdns? = null,
    private val port: Int = 5555,
    private val probeTimeout: Duration = 400.milliseconds,
    private val mdnsWindow: Duration = 3.seconds,
    private val parallelism: Int = 64,
) {
    /** Emits each hit as soon as it answers; completes when the sweep and the mDNS window end. */
    fun scan(hosts: List<String>): Flow<ScanHit> = channelFlow {
        launch { sweep(hosts) }
        mdns?.let { launch { browse(it) } }
    }

    private suspend fun ProducerScope<ScanHit>.sweep(hosts: List<String>) {
        val gate = Semaphore(parallelism)
        hosts.forEach { host ->
            launch(Dispatchers.IO) {
                gate.withPermit {
                    if (isOpen(host)) send(ScanHit(host, port, ScanHit.Kind.CLASSIC, reverseName(host)))
                }
            }
        }
    }

    private suspend fun ProducerScope<ScanHit>.browse(mdns: KadbMdns) {
        withContext(Dispatchers.IO) { mdns.start() } // JmDNS setup blocks
        try {
            val seen = mutableSetOf<Pair<String, Int>>()
            withTimeoutOrNull(mdnsWindow) {
                mdns.state.collect { state ->
                    for (e in state.allDevices) {
                        if (!seen.add(e.host to e.port)) continue
                        val kind = when (e.serviceType) {
                            MdnsServiceType.TLS_PAIRING -> ScanHit.Kind.PAIRING
                            MdnsServiceType.TLS_CONNECT -> ScanHit.Kind.WIRELESS
                            else -> ScanHit.Kind.CLASSIC
                        }
                        send(ScanHit(e.host, e.port, kind, e.name))
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { mdns.stop() }
        }
    }

    /**
     * The port [host] advertises for "pair with code" right now, or null when none shows up within the
     * mDNS window. The TV only advertises it while that dialog is open, and picks a new one each time.
     */
    suspend fun pairingPort(host: String): Int? =
        advertised { it.host == host && it.serviceType == MdnsServiceType.TLS_PAIRING }

    /**
     * The port the wireless-debugging TV named [service] (its `adb-<serial>-…` instance name) takes
     * connections on at [host] right now, or null when it doesn't show up within the mDNS window. The
     * TV picks a new one after a reboot, standby or toggle. Another TV on [host] never matches.
     */
    suspend fun connectPort(host: String, service: String): Int? =
        advertised { it.host == host && it.name == service && it.serviceType == MdnsServiceType.TLS_CONNECT }

    private suspend fun advertised(match: (MdnsEndpoint) -> Boolean): Int? {
        val mdns = mdns ?: return null
        withContext(Dispatchers.IO) { mdns.start() }
        return try {
            withTimeoutOrNull(mdnsWindow) {
                mdns.state.mapNotNull { s -> s.allDevices.firstOrNull(match)?.port }.first()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { mdns.stop() }
        }
    }

    private fun isOpen(host: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), probeTimeout.inWholeMilliseconds.toInt()); true }
    } catch (_: Exception) {
        false
    }

    private fun reverseName(host: String): String? =
        runCatching { InetAddress.getByName(host).canonicalHostName }.getOrNull()?.takeIf { it != host }

    companion object {
        /** Every other host in [address]'s subnet, capped at the surrounding /24. */
        fun subnetHosts(address: Inet4Address, prefixLength: Int = 24): List<String> {
            val bits = 32 - prefixLength.coerceIn(24, 30)
            val self = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
            val base = self and (-1 shl bits)
            return (1 until (1 shl bits) - 1).map { base + it }.filter { it != self }.map { ip ->
                (3 downTo 0).joinToString(".") { ((ip ushr (it * 8)) and 0xff).toString() }
            }
        }

        /**
         * Hosts on this machine's Wi-Fi and Ethernet interfaces, for the JVM CLI. Cellular (rmnet,
         * ccmni), VPN (tun, wg, ppp) and bridges are never swept. On Android, pass the active Wi-Fi
         * or Ethernet network's address to [subnetHosts] instead.
         */
        fun lanHosts(): List<String> = lanHosts(
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { i ->
                i.interfaceAddresses.mapNotNull { a ->
                    (a.address as? Inet4Address)?.let { Triple(i.name, it, a.networkPrefixLength.toInt()) }
                }
            },
        )

        /** [addresses]: interface name, its IPv4 address and prefix length. */
        internal fun lanHosts(addresses: List<Triple<String, Inet4Address, Int>>): List<String> =
            addresses.filter { LAN_INTERFACE.matches(it.first) }.flatMap { subnetHosts(it.second, it.third) }.distinct()

        // eth0, wlan0, and predictable names: enp3s0, wlp2s0, macOS en0.
        private val LAN_INTERFACE = Regex("eth\\d+|(en|wl)\\w+")
    }
}
