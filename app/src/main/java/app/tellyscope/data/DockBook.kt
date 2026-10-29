// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import java.io.File

/**
 * The services on each TV's Remote buttons, in order, keyed like [NameBook] so the dock follows the TV
 * across DHCP changes. `key \t package \t label` per line; null file keeps it in memory (tests,
 * the fake source).
 */
class DockBook(private val file: File? = null) {
    private val docks = linkedMapOf<String, List<Docked>>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { f.readLines() }.getOrDefault(emptyList()).forEach { line ->
                val (k, pkg, label) = line.split('\t').takeIf { it.size == 3 && it.none(String::isBlank) } ?: return@forEach
                docks[k] = docks[k].orEmpty() + Docked(pkg, label)
            }
        }
    }

    @Synchronized
    operator fun get(key: String): List<Docked> = docks[key].orEmpty()

    @Synchronized
    fun set(key: String, dock: List<Docked>) {
        val clean = dock.map { it.copy(label = it.label.replace(Regex("[\\t\\r\\n]"), " ").trim()) }
            .distinctBy { it.packageName }.take(Dock.MAX)
        if (clean.isEmpty()) docks.remove(key) else docks[key] = clean
        save()
    }

    /** The address stood in until the TV said who it is; the dock moves to the real key. */
    @Synchronized
    fun rekey(from: String, to: String) {
        if (from == to) return
        val dock = docks.remove(from) ?: return
        docks.putIfAbsent(to, dock)
        save()
    }

    private fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(docks.entries.joinToString("") { (k, dock) -> dock.joinToString("") { "$k\t${it.packageName}\t${it.label}\n" } })
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}

/** The saved buttons' rules, kept apart from the screen so they can be tested on their own. */
object Dock {
    /** The most buttons the Remote shows, on a screen 600 dp or wider; a phone shows three of them. */
    const val MAX = 4

    fun move(dock: List<Docked>, from: Int, to: Int): List<Docked> {
        if (from !in dock.indices || to !in dock.indices || from == to) return dock
        return dock.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * Splits [dock] into what is still on the TV and what was uninstalled, by [apps], the TV's
     * full package list. A disabled app stays: it's still there, and opening it says why it can't.
     * An empty list is a read that went wrong, not a TV with no apps, so nothing leaves.
     */
    fun prune(dock: List<Docked>, apps: List<AppInfo>): Pair<List<Docked>, List<Docked>> {
        if (apps.isEmpty()) return dock to emptyList()
        val installed = apps.mapTo(HashSet()) { it.packageName }
        return dock.partition { it.packageName in installed }
    }
}
