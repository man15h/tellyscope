// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.adb.RemapKeys
import java.io.File

/** What a mapped button does instead of what the TV would. */
sealed interface RemapAction {
    /** The button does nothing. */
    data object DoNothing : RemapAction

    /** The button opens [packageName]; [label] names it after the app is gone. */
    data class Open(val packageName: String, val label: String) : RemapAction
}

/**
 * One button Remap TV remote knows, by its Android key name ([RemapKeys.ALLOWED]). An app button sits
 * in one of the drawing's four app [slot]s, or under Other buttons with [NO_SLOT]; a colour key has
 * its own place. A null [action] is a learned button left to the TV. [brand] is the service printed
 * on it, where the remote is known ([RemapKeys.brand]).
 */
data class RemapButton(
    val key: String,
    val slot: Int = NO_SLOT,
    val action: RemapAction? = null,
    val brand: String? = null,
) {
    val colour: Boolean get() = key in RemapKeys.COLOURS

    companion object {
        const val NO_SLOT = -1
        const val SLOTS = 4
    }
}

/** Remap TV remote on one TV: the buttons kept for it, whether the user has it on, and whether the script runs. */
data class RemapState(val buttons: List<RemapButton> = emptyList(), val on: Boolean = false, val running: Boolean = false)

/**
 * Each TV's remapped buttons and whether remapping is on, keyed like [NameBook]. Kept on the phone,
 * so they come back after the TV restarts. `key \t button \t slot \t action \t label \t brand` per
 * button, where action is empty for none, `-` for Do nothing, or a package; `key \t ~on` when on.
 * A null file keeps it in memory (tests, the fake source).
 */
class RemapBook(private val file: File? = null) {
    private val buttons = linkedMapOf<String, List<RemapButton>>()
    private val on = mutableSetOf<String>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { f.readLines() }.getOrDefault(emptyList()).forEach { line ->
                val t = line.split('\t')
                if (t.size == 2 && t[1] == ON) {
                    on += t[0]
                    return@forEach
                }
                if (t.size != 6 || !RemapKeys.allowed(t[1])) return@forEach
                val action = when (t[3]) {
                    "" -> null
                    "-" -> RemapAction.DoNothing
                    else -> RemapAction.Open(t[3], t[4])
                }
                val b = RemapButton(t[1], t[2].toIntOrNull() ?: RemapButton.NO_SLOT, action, t[5].ifEmpty { null })
                buttons[t[0]] = buttons[t[0]].orEmpty() + b
            }
        }
    }

    @Synchronized
    operator fun get(key: String): List<RemapButton> = buttons[key].orEmpty()

    @Synchronized
    fun on(key: String) = key in on

    @Synchronized
    fun set(key: String, list: List<RemapButton>) {
        val clean = list.filter { RemapKeys.allowed(it.key) }.distinctBy { it.key }
        if (clean.isEmpty()) buttons.remove(key) else buttons[key] = clean
        save()
    }

    @Synchronized
    fun setOn(key: String, value: Boolean) {
        if (value) on += key else on -= key
        save()
    }

    /** The address stood in until the TV said who it is; its buttons move to the real key. */
    @Synchronized
    fun rekey(from: String, to: String) {
        if (from == to) return
        buttons.remove(from)?.let { buttons.putIfAbsent(to, it) }
        if (on.remove(from)) on += to
        save()
    }

    private fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        val clean = { s: String -> s.replace(Regex("[\\t\\r\\n]"), " ").trim() }
        tmp.writeText(
            buttons.entries.joinToString("") { (k, list) ->
                list.joinToString("") { b ->
                    val (action, label) = when (val a = b.action) {
                        null -> "" to ""
                        RemapAction.DoNothing -> "-" to ""
                        is RemapAction.Open -> a.packageName to clean(a.label)
                    }
                    "$k\t${b.key}\t${b.slot}\t$action\t$label\t${b.brand?.let(clean).orEmpty()}\n"
                }
            } + on.joinToString("") { "$it\t$ON\n" },
        )
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private companion object {
        const val ON = "~on"
    }
}
