// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

/** Where the TV is on the network, who it's talking to, and how much each uid has sent and received since boot. */
class NetworkStats(private val shell: AdbShell) {
    /** The TV's address, and its Wi-Fi when that's what it's on: a TV on Ethernet can still report a network it remembers. */
    suspend fun address(): Pair<Parsers.Address?, Parsers.Wifi?> {
        val (ip, status) = Commands.split(shell.shell(Commands.ADDRESS).output, 2)
        val address = Parsers.address(ip)
        return address to Parsers.wifi(status)?.takeIf { address?.iface?.startsWith("wlan") != false }
    }

    /** Live connections to somewhere off the TV; listeners and finished ones are left out. */
    suspend fun connections(): List<Parsers.TcpSocket> =
        Parsers.tcpSockets(shell.shell(Commands.SOCKETS).output).filter { it.open }

    /** Bytes per uid; empty when the TV reports neither source. */
    suspend fun traffic(): Map<Int, Parsers.Traffic> {
        val (netstats, qtaguid) = Commands.split(shell.shell(Commands.TRAFFIC).output, 2)
        return Parsers.netstatsTraffic(netstats).ifEmpty { Parsers.qtaguidTraffic(qtaguid) }
    }
}
