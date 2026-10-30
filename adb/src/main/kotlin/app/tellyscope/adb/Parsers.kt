// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** Parsers for raw shell output. Pure functions; every one returns null rather than guessing. */
object Parsers {

    /** Aggregate `cpu` line of /proc/stat. */
    data class CpuTimes(val busy: Long, val total: Long)

    fun cpuTimes(procStat: String): CpuTimes? {
        val line = procStat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val f = line.trim().split(Regex("\\s+")).drop(1).mapNotNull(String::toLongOrNull)
        if (f.size < 4) return null
        val total = f.take(8).sum() // user nice system idle iowait irq softirq steal; guest is already in user
        val idle = f[3] + f.getOrElse(4) { 0 }
        return CpuTimes(total - idle, total)
    }

    /** CPU use between two samples, 0..100, or null if the counters didn't move. */
    fun cpuPercent(prev: CpuTimes, now: CpuTimes): Double? {
        val dt = now.total - prev.total
        if (dt <= 0) return null
        return ((now.busy - prev.busy).toDouble() / dt * 100).coerceIn(0.0, 100.0)
    }

    data class Memory(val totalKb: Long, val availableKb: Long)

    fun memory(meminfo: String): Memory? {
        val kv = meminfo.lineSequence().mapNotNull { line ->
            val name = line.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.let { name to it }
        }.toMap()
        val total = kv["MemTotal"] ?: return null
        // MemAvailable exists since kernel 3.14; older TVs get a rough free+cached.
        val avail = kv["MemAvailable"] ?: ((kv["MemFree"] ?: return null) + (kv["Cached"] ?: 0))
        return Memory(total, avail)
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

    /**
     * Bytes in plus out since boot over every interface but loopback, from `/proc/net/dev`; null
     * when nothing readable. Loopback is the TV talking to itself, not network traffic.
     */
    fun netBytes(procNetDev: String): Long? {
        val totals = procNetDev.lineSequence().mapNotNull { line ->
            val iface = line.substringBefore(':', "").trim().takeIf { it.isNotEmpty() && it != "lo" } ?: return@mapNotNull null
            val f = line.substringAfter(':').trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
            if (f.size < 9) null else f[0] + f[8] // rx bytes, tx bytes
        }.toList()
        return totals.takeIf { it.isNotEmpty() }?.sum()
    }

    /**
     * When the TV's software was last updated: the security patch month, which an update moves,
     * else the month the build was made. Null when the TV reports neither.
     */
    fun softwareMonth(props: Map<String, String>): YearMonth? =
        props["ro.build.version.security_patch"]?.let { runCatching { YearMonth.parse(it.take(7)) }.getOrNull() }
            ?: props["ro.build.date.utc"]?.toLongOrNull()?.takeIf { it > 0 }
                ?.let { YearMonth.from(Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC)) }
}
