// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.R
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

/**
 * Every change Tellyscope made, per TV, plus the TV's disabled-apps list from before the first
 * one. Kept in a tab-separated file so undo and "Restore everything" survive an app restart;
 * with no file (screenshots, the fake source) it lives in memory only.
 */
class HistoryBook(private val file: File? = null) {
    private val entries = mutableListOf<HistoryEntry>()
    private val baselines = mutableMapOf<String, Set<String>>()

    init {
        file?.takeIf { it.exists() }?.readLines()?.forEach(::parse)
    }

    /** Newest first. */
    @Synchronized
    fun entries(deviceId: String): List<HistoryEntry> = entries.filter { it.deviceId == deviceId }.sortedByDescending { it.id }

    @Synchronized
    fun hasBaseline(deviceId: String) = deviceId in baselines

    /** What was already disabled before Tellyscope changed anything; written once per TV. */
    @Synchronized
    fun setBaseline(deviceId: String, disabled: Set<String>) {
        if (deviceId in baselines) return
        baselines[deviceId] = disabled
        save()
    }

    @Synchronized
    fun record(
        deviceId: String,
        at: Long,
        action: Action,
        packageName: String,
        label: String,
        undo: Action?,
        undoing: Long? = null,
        previous: Map<String, String?>? = null,
    ): HistoryEntry {
        val undone = undoing?.let { id -> entries.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        // Undoing a switch makes the old launcher Home again: a home change like any switch. Undoing
        // a first home only turns its service off; Home stays.
        val takesHome = action == Action.Home || action == Action.HomeBack ||
            undone?.let { entries[it].action == Action.Home && entries[it].firstHomeService == null } == true
        undone?.let { entries[it] = entries[it].copy(undoneAt = at) }
        if (takesHome) {
            // An older "Switched home back" would switch to a launcher that has since lost Home: its Undo goes.
            entries.replaceAll { if (it.deviceId == deviceId && it.action == Action.HomeBack && it.undoneAt == null) it.copy(undo = null) else it }
        }
        val entry = HistoryEntry((entries.maxOfOrNull { it.id } ?: 0) + 1, deviceId, at, action, packageName, label, undo, null, previous)
        entries += entry
        save()
        return entry
    }

    /** Takes entry [id]'s Undo away, for a change a later one made impossible to undo. */
    @Synchronized
    fun dropUndo(id: Long) {
        val i = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        entries[i] = entries[i].copy(undo = null)
        save()
    }

    /** Adds [extra] to entry [id]'s saved state, for a later step of the same change, and gives it [label] if set. */
    @Synchronized
    fun amend(id: Long, extra: Map<String, String?>, label: String? = null) {
        val i = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        entries[i] = entries[i].copy(previous = entries[i].previous.orEmpty() + extra, label = label?.let(::clean) ?: entries[i].label)
        save()
    }

    /**
     * Apps Tellyscope left disabled on this TV: the last disable/enable it ran was a disable, and
     * the app wasn't already off before Tellyscope's first change. Restore turns on only these.
     */
    @Synchronized
    fun restorable(deviceId: String): List<String> {
        val before = baselines[deviceId].orEmpty()
        return leftDisabled(entries.filter { it.deviceId == deviceId }).filter { it !in before }
    }

    /** The newest disable of [packageName] that nothing has undone yet: what Restore reverses. */
    @Synchronized
    fun openDisable(deviceId: String, packageName: String): Long? = entries
        .filter { it.deviceId == deviceId && it.packageName == packageName && it.disables && it.undoneAt == null }
        .maxOfOrNull { it.id }

    /**
     * Moves history recorded under [from] (a TV's address, before its serial was known) to [to].
     * A baseline already under [to] wins: it was taken earlier, before any change.
     */
    @Synchronized
    fun rekey(from: String, to: String) {
        if (from == to) return
        var changed = false
        entries.replaceAll { if (it.deviceId == from) it.copy(deviceId = to).also { changed = true } else it }
        baselines.remove(from)?.let { old -> baselines.putIfAbsent(to, old); changed = true }
        if (changed) save()
    }

    companion object {
        /**
         * Packages whose last disable/enable in [history] was a disable (a home switch disables the
         * old launcher); an uninstall clears it. Newest disable first: undoing a switch makes Home
         * the launcher it replaced, so after L→A→B, Restore undoes A→B, then L→A, and ends on L.
         * A reset nothing has undone counts as turning on each app it turned on.
         */
        fun leftDisabled(history: List<HistoryEntry>): List<String> =
            history.flatMap { e ->
                if (e.action == Action.Reset && e.undoneAt == null) ResetRecord.list(e.previous, ResetRecord.ON).map { e.copy(action = Action.Enable, packageName = it) }
                else listOf(e)
            }.filter { it.disables || it.action == Action.Enable || it.action == Action.Uninstall }
                .sortedBy { it.id }
                .associate { it.packageName to it }
                .filterValues { it.disables }
                .values.sortedByDescending { it.id }
                .map { it.packageName }
    }

    private fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val lines = baselines.map { (id, pkgs) -> listOf(VERSION, "b", id, pkgs.sorted().joinToString(",")) } +
            entries.map { e ->
                listOf(
                    VERSION, "e", e.deviceId, e.id.toString(), e.at.toString(), e.action.name, e.packageName,
                    clean(e.label), e.undo?.name.orEmpty(), e.undoneAt?.toString().orEmpty(),
                ) + listOfNotNull(e.previous?.let(::encode))
            }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(lines.joinToString("") { it.joinToString("\t") + "\n" })
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /** A damaged line costs that line only. */
    private fun parse(line: String) {
        val f = line.split('\t')
        if (f.size < 4 || f[0] != VERSION) return
        when (f[1]) {
            "b" -> baselines[f[2]] = f[3].split(',').filter { it.isNotEmpty() }.toSet()
            // An 11th field carries a setting's previous values; older entries have 10.
            "e" -> if (f.size == 10 || f.size == 11) {
                entries += HistoryEntry(
                    id = f[3].toLongOrNull() ?: return,
                    deviceId = f[2],
                    at = f[4].toLongOrNull() ?: return,
                    action = action(f[5]) ?: return,
                    packageName = f[6],
                    label = f[7],
                    undo = action(f[8]),
                    undoneAt = f[9].toLongOrNull(),
                    previous = f.getOrNull(10)?.let(::decode),
                )
            }
        }
    }

    private fun action(s: String) = Action.entries.firstOrNull { it.name == s }

    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]"), " ")

    /** "key=value,key" with values URL-encoded; a bare key was unset. */
    private fun encode(m: Map<String, String?>) =
        m.entries.joinToString(",") { (k, v) -> if (v == null) k else "$k=" + URLEncoder.encode(v, "UTF-8") }

    private fun decode(s: String): Map<String, String?> = s.split(',').filter { it.isNotEmpty() }.associate {
        it.substringBefore('=') to (if ('=' in it) URLDecoder.decode(it.substringAfter('='), "UTF-8") else null)
    }
}

private const val VERSION = "v1"

/** Where going back from a launcher the user added goes: a launcher, its home activity if known, and the switch it reverses. */
data class HomeBackTarget(val pkg: String, val activity: String?, val reverses: Long?)

/**
 * The launcher to make Home again while [current], a launcher the user added, is Home: the one
 * the newest switch to [current] replaced, else the first of the TV's own [builtIns], on or off.
 */
fun homeBackTarget(current: String, history: List<HistoryEntry>, builtIns: List<String>): HomeBackTarget? {
    val switch = history
        .filter { it.action == Action.Home && it.undoneAt == null && it.previous?.get("to")?.substringBefore('/') == current }
        .maxByOrNull { it.id }
    switch?.previous?.get("from")?.let { return HomeBackTarget(it.substringBefore('/'), it, switch.id) }
    return builtIns.firstOrNull { it != current }?.let { HomeBackTarget(it, null, null) }
}

/**
 * Going back to [pkg], one of the TV's own launchers, from the Home screen card: the newest
 * switch away from it gives its home activity and is the one reversed, so Restore leaves it alone.
 */
fun builtInTarget(pkg: String, history: List<HistoryEntry>): HomeBackTarget {
    val switch = history
        .filter { it.action == Action.Home && it.undoneAt == null && it.previous?.get("from")?.substringBefore('/') == pkg }
        .maxByOrNull { it.id }
    return HomeBackTarget(pkg, switch?.previous?.get("from"), switch?.id)
}

/**
 * A home switch disables the old launcher, unless there was none to replace or the old one was a
 * launcher the user added, which stays on ("kept"). With none to replace there's no "from"; its
 * Undo, when it turned a service on, is for that service alone. An entry without [previous]
 * predates that record and keeps counting.
 */
private val HistoryEntry.disables get() =
    action == Action.Disable || (action == Action.Home && undo != null && previous?.get("kept") == null && previous?.let { it["from"] != null } != false)

/** The service a switch with no launcher to replace turned on: its Undo turns that off, and nothing else. */
val HistoryEntry.firstHomeService: String?
    get() = previous?.takeIf { action == Action.Home && it["from"] == null }?.get("service")

/**
 * Turns apps back on one at a time and stops at the first refusal, saying how far it got so the
 * user knows what already changed. [enable] records each step.
 */
suspend fun restoreEach(packages: List<String>, enable: suspend (String) -> HistoryEntry): List<HistoryEntry> {
    val done = mutableListOf<HistoryEntry>()
    for (pkg in packages) {
        try {
            done += enable(pkg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dropped connection counts too: the user still needs to know what already changed.
            val why = e.why(R.string.data_tv_refused)
            throw UiTextException(
                if (done.isEmpty()) UiText(R.string.data_restore_none, why)
                else UiText.plural(R.plurals.data_restore_some, done.size, done.size, why),
            ).apply { initCause(e) }
        }
    }
    return done
}

/**
 * Which TV a history belongs to, stable across DHCP changes: the serial number, or the Android ID
 * when the serial is missing or one of the placeholders cheap boxes ship; the address otherwise.
 */
fun historyKey(serial: String?, androidId: String?, address: String): String {
    val sn = serial?.trim().orEmpty()
    if (sn.isNotEmpty() && sn.lowercase() !in PLACEHOLDER_SERIALS) return "sn:$sn"
    val id = androidId?.trim().orEmpty()
    if (id.isNotEmpty() && id != "null" && id.any { it != '0' }) return "id:$id"
    return address
}

private val PLACEHOLDER_SERIALS = setOf("unknown", "0123456789abcdef", "0000000000000000", "123456789", "null")
