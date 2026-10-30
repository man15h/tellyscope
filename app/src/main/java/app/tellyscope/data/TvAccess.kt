// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.adb.AdbClient
import app.tellyscope.adb.AllowedKey
import java.io.File

/**
 * Who can use ADB on a TV ([allowed]) and who is connected now ([connected]). Either is null
 * when the TV wouldn't say, which reads as "can't check", never as nobody. The two lists are
 * never tied together: the TV doesn't say which key is behind which connection.
 */
data class TvAccess(
    val connected: List<AccessClient>? = null,
    val allowed: List<AccessKey>? = null,
) {
    /**
     * Connections the warning is about: neither this phone nor an address the user said is theirs.
     * One [AccessClient.Who.Waiting] isn't in yet, so it doesn't count.
     */
    val others: List<AccessClient> get() = connected.orEmpty().filter { it.who == AccessClient.Who.Other || it.who == AccessClient.Who.OnTv }

    val warning: Boolean get() = others.isNotEmpty()

    /** The same, with [names] on the computers they belong to (address to name). */
    fun named(names: Map<String, String>): TvAccess =
        copy(connected = connected?.map { c -> names[c.address]?.let { c.copy(name = it) } ?: c })

    /** Checked, not assumed: both lists read, no other connection, and no allowed key but this phone's. */
    val onlyThisPhone: Boolean get() = connected != null && allowed != null && others.isEmpty() && allowed.none { !it.phone }

    companion object {
        /**
         * Sorts the TV's open connections into this phone, the user's own and the rest. A
         * loopback connection is this phone only when Tellyscope itself reaches the TV over
         * loopback ([tvHost], as through an emulator's forward); otherwise it is an app on the TV.
         *
         * [pending]: the fingerprint on the TV's "Allow debugging?" prompt now, empty when the prompt
         * can't be read, null when there is none. A prompt for a key that isn't allowed means a
         * connection is waiting on it; with exactly one other connection, from an address with no other, that one is
         * [AccessClient.Who.Waiting]. With more, nothing says which, so none is.
         *
         * [known]: the keys this TV was seen to allow before, null on the first read, when every key
         * counts as known. A key that isn't is [AccessKey.new].
         *
         * Leftover connections: when the TV's allowed list can be read and holds only this phone's
         * key, only this phone and a [AccessClient.Who.Waiting] connection are listed. Any other open
         * connection was never saved as allowed. It's left over from a Deny, a closed prompt or a
         * revoke, and the TV doesn't say which, so it is left out. One gap: a computer let in without
         * "Always allow" isn't saved either, so it is left out too.
         */
        fun of(
            clients: List<AdbClient>?,
            keys: List<AllowedKey>?,
            phoneAddresses: Set<String>,
            tvHost: String,
            mine: Set<String>,
            phoneFingerprint: String?,
            pending: String? = null,
            known: Set<String>? = null,
        ): TvAccess {
            val tvLoopback = tvHost == "localhost" || AdbClient(tvHost, 0).loopback
            val connected = clients?.groupBy { it.address }?.map { (address, rows) ->
                val loopback = rows.first().loopback
                val phone = if (loopback) tvLoopback else address in phoneAddresses
                val who = when {
                    phone -> AccessClient.Who.Phone
                    address in mine -> AccessClient.Who.Mine
                    loopback -> AccessClient.Who.OnTv
                    else -> AccessClient.Who.Other
                }
                AccessClient(address, rows.size, who)
            }?.let { list ->
                // A client waiting on the prompt holds one connection; a second from that address is a live session.
                val waiting = pending != null && keys?.none { it.fingerprint == pending } != false &&
                    list.singleOrNull { it.who == AccessClient.Who.Other }?.connections == 1
                if (waiting) list.map { if (it.who == AccessClient.Who.Other) it.copy(who = AccessClient.Who.Waiting) else it } else list
            }?.sortedBy { it.who.ordinal }
            val allowed = keys?.map { k ->
                val phone = k.fingerprint == phoneFingerprint
                AccessKey(k.fingerprint, k.name, k.lastSeen, phone, new = !phone && known != null && k.fingerprint !in known)
            }?.sortedWith(compareByDescending<AccessKey> { it.phone }.thenByDescending { it.new })
            val nobodyElse = allowed?.none { !it.phone } == true
            val listed = if (nobodyElse) connected?.filter { it.who == AccessClient.Who.Phone || it.who == AccessClient.Who.Waiting } else connected
            return TvAccess(listed, allowed)
        }
    }
}

/**
 * [name]: the address's network name (see [NetworkNames]), null when none answered in time. It
 * describes the address only, never which key is behind it.
 */
data class AccessClient(val address: String, val connections: Int, val who: Who, val name: String? = null) {
    /** [Waiting]: its "Allow debugging?" prompt is on the TV, so it can't do anything yet. */
    enum class Who { Phone, Mine, Waiting, Other, OnTv }
}

/**
 * An allowed computer. [lastSeen] is rough: the TV stamps keys, not sessions. [new]: allowed while
 * Tellyscope wasn't watching, until the user says it's theirs.
 */
data class AccessKey(val fingerprint: String, val name: String?, val lastSeen: Long?, val phone: Boolean = false, val new: Boolean = false)

/**
 * A set of strings per TV, keyed like [NameBook]: the addresses the user marked "It's mine", or the
 * keys a TV was seen to allow. `key \t value` per line; null file keeps it in memory (tests, the fake source).
 */
class MineBook(private val file: File? = null) {
    private val mine = linkedMapOf<String, Set<String>>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { f.readLines() }.getOrDefault(emptyList()).forEach { line ->
                val (k, a) = line.split('\t').takeIf { it.size == 2 && it.none(String::isBlank) } ?: return@forEach
                mine[k] = mine[k].orEmpty() + a
            }
        }
    }

    @Synchronized
    operator fun get(key: String): Set<String> = mine[key].orEmpty()

    /** Whether [key] has a set at all, even one that was never added to since. */
    @Synchronized
    fun has(key: String): Boolean = key in mine

    @Synchronized
    fun set(key: String, values: Set<String>) {
        mine[key] = values.filter { it.isNotBlank() && '\t' !in it && '\n' !in it }.toSet()
        save()
    }

    @Synchronized
    fun add(key: String, address: String) {
        val clean = address.trim().takeIf { it.isNotEmpty() && '\t' !in it && '\n' !in it } ?: return
        mine[key] = mine[key].orEmpty() + clean
        save()
    }

    /** The address stood in until the TV said who it is; the marks move to the real key. */
    @Synchronized
    fun rekey(from: String, to: String) {
        if (from == to) return
        val moved = mine.remove(from) ?: return
        mine[to] = mine[to].orEmpty() + moved
        save()
    }

    private fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(mine.entries.joinToString("") { (k, set) -> set.joinToString("") { "$k\t$it\n" } })
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
