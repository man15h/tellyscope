// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64

/**
 * A computer the TV allows to use ADB: a key from its allowed list. [name] is the comment adb
 * keeps after the key, like `alex@laptop`; null when there is none. [lastSeen] is the TV's own
 * `lastConnection`, epoch millis; it is rough (keys seen together share one time), never a session.
 */
data class AllowedKey(val fingerprint: String, val name: String?, val lastSeen: Long?)

/** One open ADB connection to the TV, by the address it comes from. Nothing ties it to a key. */
data class AdbClient(val address: String, val port: Int) {
    /** From the TV itself, like an ADB app running on it. */
    val loopback get() = address == "127.0.0.1" || address == "::1"
}

/**
 * Who can use ADB on the TV, and who is connected now. Both come from places the shell user may
 * read; `/data/misc/adb` itself is not one of them. Each read answers null when the TV won't say,
 * which the screens show as "can't check", never as nobody.
 */
class Access(private val shell: AdbShell) {

    /** The allowed computers, from `dumpsys adb` (Android 11+); null when the TV won't say. */
    suspend fun keys(): List<AllowedKey>? = keys(shell.shell("dumpsys adb").output)

    /**
     * Open connections to adbd: on 5555, [port] (the one Tellyscope uses) and the
     * wireless-debugging port when one is set. Null when the TV won't list them. [tables], the
     * TCP tables just read for something else, saves reading them again.
     */
    suspend fun clients(port: Int, tables: String? = null): List<AdbClient>? {
        val tls = shell.shell("getprop service.adb.tls.port").output.trim().toIntOrNull()
        val ports = setOfNotNull(CLASSIC_PORT, port, tls?.takeIf { it > 0 })
        return clients(tables ?: shell.shell("cat /proc/net/tcp6 /proc/net/tcp 2>/dev/null").output, ports)
    }

    /**
     * The TV's own "Revoke USB debugging authorisations": IAdbManager.clearDebuggingKeys, which the
     * shell user may call. Every allowed computer goes, this phone too; open connections may stay
     * up until they reconnect. False when the TV refused the call, and without calling anything
     * on an Android whose transaction code isn't in [CLEAR_KEYS]: the manual guide covers those.
     */
    suspend fun revokeAll(): Boolean {
        val sdk = shell.shell("getprop ro.build.version.sdk").output.trim().toIntOrNull()
        val code = CLEAR_KEYS[sdk] ?: return false
        val r = shell.shell("service call adb $code 2>&1")
        return r.exitCode == 0 && r.output.contains("Parcel(00000000")
    }

    /** Opens Developer options on the TV, where "Revoke USB debugging authorisations" is. False when nothing opened. */
    suspend fun openDeveloperOptions(): Boolean {
        val r = shell.shell("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS 2>&1")
        return r.exitCode == 0 && "Error" !in r.output
    }

    companion object {
        const val CLASSIC_PORT = 5555

        /**
         * clearDebuggingKeys' binder transaction by API level: its 1-based place in IAdbManager.aidl,
         * read from each AOSP release branch (android11 to android16). A level not listed is never
         * called, since the same number may be another method there.
         */
        val CLEAR_KEYS = (30..36).associateWith { 3 }

        /** adb writes keys base64; the struct starts with its word count, 64, so every one begins `QAAAA`. */
        private val KEY = Regex("(QAAAA[A-Za-z0-9+/]+={0,2})(?:[ \\t]+([^\\r\\n\"]*))?")
        private val ADB_KEY_TAG = Regex("<adbKey\\b[^>]*>")
        private val ATTR = Regex("([\\w-]+)=\"([^\"]*)\"")
        /** Where the next field of `dumpsys adb` starts, so `user_keys` ends before it. */
        private val NEXT_FIELD = Regex("^\\s*(?:[a-z_]+=|[{}])", RegexOption.MULTILINE)

        /**
         * `dumpsys adb` prints the allowed-keys file as `user_keys`, and the key store as
         * `keystore` with a `lastConnection` per key. No `user_keys` under a readable debugging
         * manager means the file is gone, as after a revoke: nobody is allowed.
         */
        fun keys(dumpsys: String): List<AllowedKey>? {
            if ("debugging_manager" !in dumpsys) return null
            // The key store's entries are the key and its comment, with when it was last used.
            val stored = ADB_KEY_TAG.findAll(dumpsys).mapNotNull { tag ->
                val attrs = ATTR.findAll(tag.value).associate { it.groupValues[1] to xml(it.groupValues[2]) }
                attrs["key"]?.trim()?.let { it to attrs["lastConnection"]?.toLongOrNull()?.takeIf { t -> t > 0 } }
            }.toList()
            val listed = dumpsys.indexOf("user_keys=").takeIf { it >= 0 }?.let { start ->
                val from = start + "user_keys=".length
                val end = NEXT_FIELD.find(dumpsys, from)?.range?.first ?: dumpsys.length
                dumpsys.substring(from, end).lines().map { it.trim() }
            }.orEmpty()
            val stamps = stored.mapNotNull { (line, at) -> KEY.matchEntire(line)?.let { it.groupValues[1] to at } }.toMap()
            // The file's keys first, in its order, then any only the key store knows.
            return (listed + stored.map { it.first }).mapNotNull { line ->
                val m = KEY.matchEntire(line) ?: return@mapNotNull null
                val fp = fingerprint(m.groupValues[1]) ?: return@mapNotNull null
                AllowedKey(fp, m.groupValues[2].trim().takeIf { it.isNotEmpty() }, stamps[m.groupValues[1]])
            }.distinctBy { it.fingerprint }
        }

        /** The MD5 the TV's "Allow debugging?" prompt shows for a base64 adb key; null if it isn't one. */
        fun fingerprint(base64: String): String? = runCatching {
            MessageDigest.getInstance("MD5").digest(Base64.getDecoder().decode(base64)).joinToString(":") { "%02X".format(it) }
        }.getOrNull()

        /**
         * ESTABLISHED connections whose local port is one of [ports], from `/proc/net/tcp6` and
         * `/proc/net/tcp` together. IPv4 clients of an IPv6 socket show as `::ffff:` addresses
         * and come back as plain IPv4. Null when neither table was there to read.
         */
        fun clients(proc: String, ports: Set<Int>): List<AdbClient>? {
            if ("local_address" !in proc) return null
            return proc.lines().mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 4 || !cols[0].endsWith(":") || cols[3] != ESTABLISHED) return@mapNotNull null
                val (localPort, remote) = endpoint(cols[1])?.second to endpoint(cols[2])
                if (localPort !in ports || remote == null) return@mapNotNull null
                AdbClient(remote.first, remote.second)
            }.distinct()
        }

        private const val ESTABLISHED = "01"

        private fun endpoint(s: String): Pair<String, Int>? {
            val (hex, port) = s.split(':').takeIf { it.size == 2 } ?: return null
            return address(hex)?.let { it to (port.toIntOrNull(16) ?: return null) }
        }

        /**
         * A kernel socket address: 8 or 32 hex digits in 32-bit words, each in the CPU's
         * little-endian order. `::ffff:a.b.c.d` comes back as `a.b.c.d`.
         */
        fun address(hex: String): String? {
            if (hex.length != 8 && hex.length != 32) return null
            val bytes = runCatching {
                hex.chunked(8).flatMap { word -> word.chunked(2).map { it.toInt(16).toByte() }.reversed() }.toByteArray()
            }.getOrNull() ?: return null
            val mapped = bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
            val raw = if (mapped) bytes.copyOfRange(12, 16) else bytes
            return InetAddress.getByAddress(raw).hostAddress?.let { if (it == "0:0:0:0:0:0:0:1") "::1" else it }
        }

        private fun xml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}
