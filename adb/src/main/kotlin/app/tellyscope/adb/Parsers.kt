// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** Parsers for raw shell output. Pure functions; every one returns null rather than guessing. */
object Parsers {

    /**
     * One `cpu` line of /proc/stat. [busy] leaves out time waiting on storage, which is idle to the
     * CPU; [user], [system] and [ioWait] split the ticks for the CPU tab.
     */
    data class CpuTimes(val busy: Long, val total: Long, val user: Long = 0, val system: Long = 0, val ioWait: Long = 0)

    /** The aggregate `cpu` line. */
    fun cpuTimes(procStat: String): CpuTimes? =
        procStat.lineSequence().firstOrNull { it.startsWith("cpu ") }?.let(::cpuLine)

    /** Each core's `cpuN` line by N. A core the kernel has switched off has no line. */
    fun coreTimes(procStat: String): Map<Int, CpuTimes> = procStat.lineSequence().mapNotNull { line ->
        val n = CORE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        cpuLine(line)?.let { n to it }
    }.toMap()

    private val CORE = Regex("""^cpu(\d+)\s""")

    private fun cpuLine(line: String): CpuTimes? {
        val f = line.trim().split(Regex("\\s+")).drop(1).mapNotNull(String::toLongOrNull)
        if (f.size < 4) return null
        fun at(i: Int) = f.getOrElse(i) { 0 }
        val total = f.take(8).sum() // user nice system idle iowait irq softirq steal; guest is already in user
        val idle = at(3) + at(4)
        return CpuTimes(total - idle, total, user = at(0) + at(1), system = at(2) + at(5) + at(6) + at(7), ioWait = at(4))
    }

    /** CPU use between two samples, 0..100, or null if the counters didn't move. */
    fun cpuPercent(prev: CpuTimes, now: CpuTimes): Double? {
        val dt = now.total - prev.total
        if (dt <= 0) return null
        return ((now.busy - prev.busy).toDouble() / dt * 100).coerceIn(0.0, 100.0)
    }

    /** Where the CPU's time went between two samples, each 0..100 of all of it. */
    data class CpuSplit(val apps: Double, val system: Double, val ioWait: Double)

    fun cpuSplit(prev: CpuTimes, now: CpuTimes): CpuSplit? {
        val dt = (now.total - prev.total).takeIf { it > 0 }?.toDouble() ?: return null
        fun pct(a: Long, b: Long) = ((b - a) / dt * 100).coerceIn(0.0, 100.0)
        return CpuSplit(pct(prev.user, now.user), pct(prev.system, now.system), pct(prev.ioWait, now.ioWait))
    }

    /**
     * The chip by name: `ro.soc.*` from Android 12 (`Amlogic S905X4`), else the board's platform
     * code (`mt5867`), which is still what a search finds.
     */
    fun chip(props: Map<String, String>): String? {
        val model = props["ro.soc.model"]?.takeIf { it.isNotBlank() }
        if (model != null) {
            val maker = props["ro.soc.manufacturer"]?.takeIf { it.isNotBlank() && !model.startsWith(it, ignoreCase = true) }
            return listOfNotNull(maker, model).joinToString(" ")
        }
        return (props["ro.board.platform"] ?: props["ro.hardware"])?.takeIf { it.isNotBlank() }
    }

    /** The fastest core's clock now, in kHz, from each core's `scaling_cur_freq`; null where cpufreq is hidden. */
    fun clockKhz(freqs: String): Long? = freqs.lineSequence().mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.maxOrNull()

    /**
     * [availableKb] is what apps could still get; of that, [freeKb] holds nothing and the rest is
     * cache the TV gives back when asked. Swap is zram on TVs, 0 where there's none.
     */
    data class Memory(val totalKb: Long, val availableKb: Long, val freeKb: Long = 0, val swapTotalKb: Long = 0, val swapFreeKb: Long = 0)

    fun memory(meminfo: String): Memory? {
        val kv = meminfo.lineSequence().mapNotNull { line ->
            val name = line.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.let { name to it }
        }.toMap()
        val total = kv["MemTotal"] ?: return null
        // MemAvailable exists since kernel 3.14; older TVs get a rough free+cached.
        val free = kv["MemFree"]
        val avail = kv["MemAvailable"] ?: ((free ?: return null) + (kv["Cached"] ?: 0))
        return Memory(total, avail, (free ?: 0).coerceAtMost(avail), kv["SwapTotal"] ?: 0, kv["SwapFree"] ?: 0)
    }

    data class Storage(val totalKb: Long, val usedKb: Long)

    /** `df -k /data`. Toybox may wrap a long filesystem name onto its own line. */
    fun storage(df: String): Storage? {
        val nums = df.lineSequence().drop(1).joinToString(" ").trim().split(Regex("\\s+"))
            .mapNotNull(String::toLongOrNull)
        if (nums.size < 2) return null
        return Storage(totalKb = nums[0], usedKb = nums[1])
    }

    fun uptimeSeconds(procUptime: String): Long? =
        procUptime.trim().substringBefore(' ').toDoubleOrNull()?.toLong()

    data class Package(val name: String, val uid: Int?, val versionCode: Long? = null)

    /**
     * `pm list packages [--show-versioncode] [-U]`: `package:com.foo versionCode:42 uid:10023`
     * (uid list may be comma-separated). Each package once: some TVs print one twice, and the
     * app list is keyed on the name.
     */
    fun packages(pmList: String): List<Package> = pmList.lineSequence().mapNotNull { line ->
        val l = line.trim()
        if (!l.startsWith("package:")) return@mapNotNull null
        val name = l.removePrefix("package:").substringBefore(' ')
        val uid = l.substringAfter(" uid:", "").substringBefore(',').toIntOrNull()
        val version = l.substringAfter(" versionCode:", "").substringBefore(' ').toLongOrNull()
        Package(name, uid, version).takeIf { name.isNotEmpty() }
    }.distinctBy { it.name }.toList()

    /**
     * Packages the TV disabled itself, from [Commands.ENABLED_STATES]: enabled state 2 (disabled)
     * or 4 (disabled until used). 3 is the user's own disable, which ADB can undo. Hidden system
     * packages are listed again after the installed ones, so each package's first state counts.
     */
    fun disabledByTv(dump: String): Set<String> {
        val states = mutableMapOf<String, Int>()
        var pkg: String? = null
        for (line in dump.lineSequence().map(String::trim)) {
            if (line.startsWith("Package [")) pkg = line.substringAfter('[').substringBefore(']')
            else if (line.startsWith("User 0:")) {
                val state = line.substringAfter(" enabled=", "").substringBefore(' ').toIntOrNull()
                if (pkg != null && state != null) states.putIfAbsent(pkg, state)
                pkg = null
            }
        }
        return states.filterValues { it == 2 || it == 4 }.keys
    }

    /** [cpuPercent] is null from [psProcesses], which reads memory only. */
    data class Process(val pid: Int, val user: String, val rssKb: Long, val cpuPercent: Double?, val name: String)

    /**
     * `top -b -n 1 -o PID,USER,RES,%CPU,NAME`. Header and summary lines are skipped by requiring a
     * numeric first column. RES carries K/M/G suffixes.
     */
    fun processes(top: String): List<Process> = top.lineSequence().mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"), limit = 5)
        if (f.size < 5) return@mapNotNull null
        val pid = f[0].toIntOrNull() ?: return@mapNotNull null
        val rss = sizeKb(f[2]) ?: return@mapNotNull null
        val cpu = f[3].toDoubleOrNull() ?: return@mapNotNull null
        Process(pid, f[1], rss, cpu, f[4])
    }.toList()

    /**
     * `ps -A -o PID,USER,RSS,NAME`, or plain `ps` where `-o` isn't taken. Columns are found by the
     * header and the name is the last field, since old toolbox `ps` prints a state column it doesn't
     * name. RSS is in KB; CPU is left null.
     */
    fun psProcesses(ps: String): List<Process> {
        val lines = ps.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        val header = lines.firstOrNull()?.split(Regex("\\s+")) ?: return emptyList()
        val pid = header.indexOf("PID")
        val user = header.indexOf("USER")
        val rss = header.indexOf("RSS")
        if (pid < 0 || user < 0 || rss < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val f = line.split(Regex("\\s+"))
            if (f.size < header.size) return@mapNotNull null
            Process(f[pid].toIntOrNull() ?: return@mapNotNull null, f[user], f[rss].toLongOrNull() ?: return@mapNotNull null, null, f.last())
        }.toList()
    }

    /** `u0_a123` → 10123, `system` etc. → null. */
    fun appUid(user: String): Int? {
        val m = Regex("u(\\d+)_a(\\d+)").matchEntire(user) ?: return null
        return m.groupValues[1].toInt() * 100_000 + 10_000 + m.groupValues[2].toInt()
    }

    /**
     * The package a process belongs to, from [byUid] (`pm list packages -U`). The process name wins
     * (`com.foo:remote` is com.foo, even under the shared system uid); otherwise the app uid's package
     * whose name starts the process name, else its first. Null for native daemons.
     */
    fun owner(p: Process, byUid: Map<Int, List<String>>, known: Set<String> = byUid.values.flatten().toSet()): String? {
        val base = p.name.substringBefore(':')
        if (base in known) return base
        val pkgs = appUid(p.user)?.let { byUid[it] }.orEmpty()
        return pkgs.firstOrNull { p.name.startsWith(it) } ?: pkgs.firstOrNull()
    }

    /** Uid → its packages; shared uids hold several. */
    fun byUid(packages: List<Package>): Map<Int, List<String>> =
        packages.filter { it.uid != null }.groupBy({ it.uid!! }, { it.name })

    /**
     * `dumpsys power`, or just its wakefulness lines. Dreaming is a screensaver, so the screen is on.
     * Null when neither line is there.
     */
    fun screenOn(power: String): Boolean? {
        Regex("mWakefulness=(\\w+)").find(power)?.let { return it.groupValues[1] in setOf("Awake", "Dreaming") }
        return Regex("mScreenOn=(true|false)").find(power)?.groupValues?.get(1)?.toBooleanStrict()
    }

    data class TcpSocket(val uid: Int, val remoteIp: String, val remotePort: Int, val state: Int) {
        /** Loopback or unconnected: nothing leaves the TV. */
        val local get() = remotePort == 0 || remoteIp.startsWith("127.") || remoteIp == "0:0:0:0:0:0:0:1"

        /** Talking to another machine now: not listening, and not already closed (TIME_WAIT has lost its uid). */
        val open get() = !local && state != LISTEN && state != TIME_WAIT && state != CLOSE

        val remote get() = if (':' in remoteIp) "[$remoteIp]:$remotePort" else "$remoteIp:$remotePort"

        companion object {
            /** Kernel TCP states, as /proc/net/tcp numbers them. */
            const val ESTABLISHED = 0x01
            const val SYN_SENT = 0x02
            const val SYN_RECV = 0x03
            const val TIME_WAIT = 0x06
            const val CLOSE = 0x07
            const val LISTEN = 0x0A
        }
    }

    /**
     * `/proc/net/tcp` and `tcp6` rows: `sl local rem st queues timers retrnsmt uid ...`. Addresses are
     * hex in the kernel's byte order (each 32-bit word little-endian); IPv4-mapped IPv6 comes out as IPv4.
     */
    fun tcpSockets(procNet: String): List<TcpSocket> = procNet.lineSequence().mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 8 || !f[0].endsWith(':')) return@mapNotNull null
        val remote = f[2].split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val ip = hexIp(remote[0]) ?: return@mapNotNull null
        val port = remote[1].toIntOrNull(16) ?: return@mapNotNull null
        val state = f[3].toIntOrNull(16) ?: return@mapNotNull null
        val uid = f[7].toIntOrNull() ?: return@mapNotNull null
        TcpSocket(uid, ip, port, state)
    }.toList()

    private fun hexIp(hex: String): String? {
        if (hex.length != 8 && hex.length != 32) return null
        val bytes = hex.chunked(8).flatMap { word ->
            word.chunked(2).map { it.toIntOrNull(16) ?: return null }.reversed()
        }
        val v4 = when {
            bytes.size == 4 -> bytes
            bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff -> bytes.drop(12)
            else -> null
        }
        if (v4 != null) return v4.joinToString(".")
        return java.net.InetAddress.getByAddress(ByteArray(16) { bytes[it].toByte() }).hostAddress?.substringBefore('%')
    }

    data class Traffic(val rxBytes: Long, val txBytes: Long)

    /**
     * Bytes per uid from the `UID stats:` section of `dumpsys netstats --uid`: `ident=... uid=N ...`
     * lines, each followed by `st=... rb=... tb=...` buckets that add up.
     */
    fun netstatsTraffic(dump: String): Map<Int, Traffic> {
        val out = mutableMapOf<Int, Traffic>()
        var inUid = false
        var uid: Int? = null
        for (line in dump.lineSequence()) {
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                inUid = line.trim() == "UID stats:"
                uid = null
                continue
            }
            if (!inUid) continue
            val ident = Regex("\\buid=(-?\\d+)").find(line)
            if (ident != null) {
                uid = ident.groupValues[1].toInt()
                continue
            }
            val u = uid ?: continue
            val rb = Regex("\\brb=(\\d+)").find(line)?.groupValues?.get(1)?.toLong() ?: continue
            val tb = Regex("\\btb=(\\d+)").find(line)?.groupValues?.get(1)?.toLong() ?: 0
            val t = out[u] ?: Traffic(0, 0)
            out[u] = Traffic(t.rxBytes + rb, t.txBytes + tb)
        }
        return out
    }

    /** `/proc/net/xt_qtaguid/stats`, untagged rows only (tagged ones are already in them). */
    fun qtaguidTraffic(stats: String): Map<Int, Traffic> {
        val out = mutableMapOf<Int, Traffic>()
        stats.lineSequence().drop(1).forEach { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 8 || f[2] != "0x0") return@forEach
            val uid = f[3].toIntOrNull() ?: return@forEach
            val rx = f[5].toLongOrNull() ?: return@forEach
            val tx = f[7].toLongOrNull() ?: return@forEach
            val t = out[uid] ?: Traffic(0, 0)
            out[uid] = Traffic(t.rxBytes + rx, t.txBytes + tx)
        }
        return out
    }

    internal fun sizeKb(s: String): Long? {
        val n = s.dropLast(1).toDoubleOrNull()
        return when (s.lastOrNull()?.uppercaseChar()) {
            'K' -> n?.toLong()
            'M' -> n?.let { (it * 1024).toLong() }
            'G' -> n?.let { (it * 1024 * 1024).toLong() }
            else -> s.toLongOrNull()?.let { it / 1024 } // bare bytes
        }
    }

    /** `getprop` lines: `[ro.product.model]: [XK03H]`. */
    fun props(getprop: String): Map<String, String> = getprop.lineSequence().mapNotNull { line ->
        val m = Regex("""\[(.+?)]: \[(.*)]""").matchEntire(line.trim()) ?: return@mapNotNull null
        m.groupValues[1] to m.groupValues[2]
    }.toMap()

    /** Bytes received and sent since boot. */
    data class NetBytes(val rx: Long, val tx: Long)

    /**
     * Bytes in and out since boot over every interface but loopback, from `/proc/net/dev`; null
     * when nothing readable. Loopback is the TV talking to itself, not network traffic.
     */
    fun netBytes(procNetDev: String): NetBytes? {
        val totals = procNetDev.lineSequence().mapNotNull { line ->
            val iface = line.substringBefore(':', "").trim().takeIf { it.isNotEmpty() && it != "lo" } ?: return@mapNotNull null
            val f = line.substringAfter(':').trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
            if (f.size < 9) null else NetBytes(f[0], f[8])
        }.toList()
        return totals.takeIf { it.isNotEmpty() }?.let { t -> NetBytes(t.sumOf { it.rx }, t.sumOf { it.tx }) }
    }

    /** The interface the TV is on, its IPv4 address and its hardware address. */
    data class Address(val iface: String, val ip: String, val mac: String?)

    /**
     * `ip addr show`: the first interface with an IPv4 address that isn't loopback. Apps can't
     * read `/sys/class/net/<iface>/address` on newer TVs, but `ip` still prints the `link/ether` line.
     */
    fun address(ipAddr: String): Address? {
        var iface: String? = null
        var mac: String? = null
        var ip: String? = null
        for (line in ipAddr.lineSequence() + sequenceOf("0: end:")) {
            val head = IFACE.find(line)
            if (head != null) {
                if (ip != null && iface != null && iface != "lo") return Address(iface, ip, mac)
                iface = head.groupValues[1]
                mac = null
                ip = null
                continue
            }
            val f = line.trim().split(Regex("\\s+"))
            when (f.firstOrNull()) {
                "link/ether" -> mac = f.getOrNull(1)?.takeIf { it != "00:00:00:00:00:00" }
                "inet" -> if (ip == null) ip = f.getOrNull(1)?.substringBefore('/')
            }
        }
        return null
    }

    private val IFACE = Regex("""^\d+:\s+([^:@\s]+)""")

    /** What the TV's Wi-Fi is joined to; null fields where the TV doesn't say. */
    data class Wifi(val ssid: String?, val rssi: Int?, val linkMbps: Int?)

    /**
     * `cmd wifi status` (Android 11 and later), or the `mWifiInfo` line of `dumpsys wifi` on older
     * TVs. Newer TVs print `SSID: XXX` to apps, so the name comes from `Wifi is connected to "…"`
     * where there is one. Null when the TV isn't on Wi-Fi.
     */
    fun wifi(status: String): Wifi? {
        val ssid = (CONNECTED_TO.find(status) ?: SSID.find(status))?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() && it != "XXX" && it != "<unknown ssid>" }
        val rssi = RSSI.find(status)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in -126..-1 }
        val link = LINK.find(status)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        return if (ssid == null && rssi == null) null else Wifi(ssid, rssi, link)
    }

    private val CONNECTED_TO = Regex("""connected to "(.*)"""")
    private val SSID = Regex("""\bSSID: (.*?), BSSID""")
    private val RSSI = Regex("""\bRSSI: (-?\d+)""")
    private val LINK = Regex("""(?<!Tx |Rx |Supported )Link speed: (-?\d+)\s*Mbps""")

    /**
     * When the TV's software was last updated: the security patch month, which an update moves,
     * else the month the build was made. Null when the TV reports neither.
     */
    fun softwareMonth(props: Map<String, String>): YearMonth? =
        props["ro.build.version.security_patch"]?.let { runCatching { YearMonth.parse(it.take(7)) }.getOrNull() }
            ?: props["ro.build.date.utc"]?.toLongOrNull()?.takeIf { it > 0 }
                ?.let { YearMonth.from(Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC)) }

    /**
     * Fire OS's version, like `8.1.4.9`. Fire OS 5 also has `ro.build.version.fireos`. 5 to 8
     * put it in `ro.build.version.name` (`Fire OS 8.1.4.9 (RS8149/3133)`), which other makers
     * may use for anything, so that one counts only on an Amazon-made device. Fire TV Edition sets
     * from other brands report Amazon as the manufacturer too. Null when neither has a version.
     */
    fun fireOsVersion(props: Map<String, String>): String? {
        val amazon = props["ro.product.manufacturer"].equals("Amazon", ignoreCase = true)
        return listOfNotNull(props["ro.build.version.fireos"], props["ro.build.version.name"]?.takeIf { amazon })
            .firstNotNullOfOrNull { FIRE_OS_VERSION.find(it)?.value }
    }

    private val FIRE_OS_VERSION = Regex("""\b\d+(\.\d+){2,3}\b""")
}
