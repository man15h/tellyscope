// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Sink
import java.io.IOException

/**
 * A group of global settings read and written together, so a change can be put back exactly: a
 * key that wasn't set before is deleted again, never set to a guess at the default. Keys are
 * written in [keys] order.
 */
enum class Tweak(val keys: List<String>) {
    /**
     * Android 9+. Mode `hostname` sends every lookup to `private_dns_specifier` over TLS. The
     * provider goes first, so a failed write never leaves strict mode aimed at the old one.
     */
    PRIVATE_DNS(listOf("private_dns_specifier", "private_dns_mode")),
    ANIMATIONS(listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")),
}

/** Settings and housekeeping that touch the whole TV rather than one app. */
class Tweaks(private val session: AdbSession) {

    /** Every key of [tweak]; null for a key that isn't set. */
    suspend fun read(tweak: Tweak): Map<String, String?> {
        // `settings get` prints exactly one line per key, "null" when unset.
        val out = session.shell(tweak.keys.joinToString("; ") { "settings get global $it" }).output.lines()
        return tweak.keys.withIndex().associate { (i, k) ->
            k to out.getOrNull(i)?.trim()?.takeUnless { it.isEmpty() || it == "null" }
        }
    }

    /** Writes [values] (keys of [tweak] only); null deletes the key. False when the TV refused. */
    suspend fun write(tweak: Tweak, values: Map<String, String?>): Boolean {
        require(values.isNotEmpty() && tweak.keys.containsAll(values.keys)) { "not keys of $tweak: ${values.keys}" }
        val cmd = tweak.keys.filter { it in values }.joinToString(" && ") { k ->
            val v = values[k]
            if (v == null) "settings delete global $k" else "settings put global $k ${shellQuote(v)}"
        }
        val r = session.shell("$cmd 2>&1")
        return r.exitCode == 0 && "Exception" !in r.output
    }

    /**
     * Whether the TV can look names up, tried a few times while a new Private DNS provider is
     * checked. Strict Private DNS fails closed, so a provider the network blocks takes the whole
     * TV offline. Only "unknown host" counts as failing: a resolved name whose ping gets no
     * answer still resolved, and a TV without ping can't tell, so it passes.
     */
    suspend fun resolves(host: String = "connectivitycheck.gstatic.com", tries: Int = 3, wait: Long = 2_000): Boolean {
        repeat(tries) { i ->
            if ("unknown host" !in session.shell("ping -c1 -W3 $host 2>&1").output.lowercase()) return true
            if (i < tries - 1) delay(wait)
        }
        return false
    }

    /**
     * Whether strict Private DNS works with provider [host]: polls `dumpsys connectivity` until a
     * network names [host] as its provider and lists addresses it validated over TLS, [tries] times
     * [wait] ms apart. A lookup alone can pass on the TV's DNS cache, which a new provider doesn't
     * clear. False when [host] is named but never validated; null when it's never named, so the
     * strict mode hasn't applied, or the TV prints it in a way this doesn't read.
     */
    suspend fun dnsValidated(host: String, tries: Int = 8, wait: Long = 2_000): Boolean? {
        var named = false
        repeat(tries) { i ->
            // Android 9 on prints a network's provider as "PrivateDnsServerName: <host> " in its link properties.
            val lines = session.shell("dumpsys connectivity | grep -F ${shellQuote("PrivateDnsServerName: $host ")}").output.lines().filter { it.isNotBlank() }
            if (lines.any { "ValidatedPrivateDnsAddresses: [" in it }) return true
            if (lines.isNotEmpty()) named = true
            if (i < tries - 1) delay(wait)
        }
        return if (named) false else null
    }

    /**
     * Asks Android to free cache space from every app (`pm trim-caches` with more than any TV
     * has); app data and sign-ins stay. Returns the space freed in KB, or null when the TV
     * doesn't report its storage.
     */
    suspend fun clearCaches(): Long? {
        val before = freeKb()
        val r = session.shell("pm trim-caches 999G 2>&1")
        if (r.exitCode != 0 || "Exception" in r.output || "Error" in r.output) {
            throw IOException(r.output.trim().ifEmpty { "the TV refused" })
        }
        val after = freeKb()
        return if (before != null && after != null) (after - before).coerceAtLeast(0) else null
    }

    private suspend fun freeKb() = Parsers.storage(session.shell("df -k /data").output)?.let { it.totalKb - it.usedKb }

    /**
     * A PNG of what the TV shows, into [sink]. `screencap` writes to a file on the TV first: shell
     * output is text, and a PNG through it would be mangled. Protected video shows as black.
     */
    suspend fun screenshot(sink: Sink) {
        val r = session.shell("screencap -p $SHOT 2>&1")
        if (r.exitCode != 0) throw IOException(r.output.trim().ifEmpty { "the TV couldn't take a screenshot" })
        try {
            runTransfer { session.pull(sink, SHOT) }
        } finally {
            withContext(NonCancellable) { runCatching { session.shell("rm -f $SHOT") } }
        }
    }

    companion object {
        private const val SHOT = "/data/local/tmp/tellyscope-shot.png"
        private val HOSTNAME = Regex("(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}")

        /** A Private DNS provider must be a hostname: Android doesn't take an IP address there. */
        fun isHostname(s: String) = HOSTNAME.matches(s)
    }
}
