// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

/** What the Shell terminal makes of a typed or pasted line before anything reaches the TV. */
sealed interface ShellLine {
    data object Empty : ShellLine

    /** Runs [command]; [dropped] when a pasted `adb shell` was taken off the front. */
    data class Run(val command: String, val dropped: Boolean) : ShellLine

    /** An `adb` command that isn't `adb shell` (`adb install …`): it runs on a computer, not on the TV. */
    data object NotShell : ShellLine

    /** [program] needs a full-screen terminal, which this one isn't. */
    data class FullScreen(val program: String) : ShellLine
}

object ShellInput {
    private val ADB = Regex("""^adb(?:\s+-[sdeHPL]\s*\S*|\s+-[a-zA-Z])*\s+""")
    private val SHELL = Regex("""^shell(?:\s+|$)""")
    private val SEGMENTS = Regex("""\|\||&&|[;|&\n]""")

    /** Full-screen programs that wait on a terminal forever; `top` is fine once it's told to print a set number of times. */
    private val FULL_SCREEN = setOf("vi", "vim", "nano", "less", "more", "htop", "screen", "tmux")

    /**
     * The commands that change the TV, each with its leading words. A match asks before it runs,
     * because Shell runs aren't in History and have no one-tap undo.
     */
    private val CHANGES = listOf(
        "pm uninstall", "pm disable", "pm disable-user", "pm clear",
        "cmd package uninstall", "cmd package disable", "cmd package disable-user", "cmd package clear",
        "settings put", "settings delete",
        "wm size", "wm density",
        "reboot", "svc power reboot", "rm",
    ).map { it.split(' ') }

    fun parse(line: String): ShellLine {
        var s = line.trim()
        var dropped = false
        ADB.find(s)?.let { m ->
            val rest = s.substring(m.range.last + 1)
            val shell = SHELL.find(rest) ?: return ShellLine.NotShell
            s = rest.substring(shell.range.last + 1).trim()
            dropped = true
        }
        if (s.isEmpty()) return ShellLine.Empty
        segments(s).firstNotNullOfOrNull { words ->
            val name = words.firstOrNull()?.substringAfterLast('/') ?: return@firstNotNullOfOrNull null
            when {
                name in FULL_SCREEN -> name
                name == "top" && words.none { it == "-n" || it.startsWith("-n") } -> name
                else -> null
            }
        }?.let { return ShellLine.FullScreen(it) }
        return ShellLine.Run(s, dropped)
    }

    /** The part of [command] that changes the TV, as typed (`pm uninstall`), or null when nothing does. */
    fun changesTv(command: String): String? = segments(command).firstNotNullOfOrNull { words ->
        CHANGES.filter { it.size <= words.size && words.subList(0, it.size) == it }
            // `wm size` on its own only prints the size.
            .filterNot { it[0] == "wm" && words.size == it.size }
            .maxByOrNull { it.size }
            ?.joinToString(" ")
    }

    /** Each simple command's words, with `su -c`, `exec`-like wrappers and `VAR=x` prefixes skipped. */
    private fun segments(command: String): List<List<String>> = command.split(SEGMENTS).map { seg ->
        seg.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            .dropWhile { it == "su" || it == "-c" || it == "0" || it == "exec" || it == "command" || it == "nohup" || it.matches(Regex("""\w+=\S*""")) }
            .map { it.trim('\'', '"', '(', ')', '{', '}') }
    }
}
