// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import app.tellyscope.R
import app.tellyscope.adb.ShellChunk
import app.tellyscope.adb.ShellEnd
import app.tellyscope.adb.ShellInput
import app.tellyscope.adb.ShellLine
import app.tellyscope.adb.TvShell
import app.tellyscope.data.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One line of the terminal. */
sealed interface TermLine {
    /** A command as it was entered, after the prompt it was entered at. */
    data class Typed(val cwd: String, val command: String) : TermLine
    data class Out(val text: String) : TermLine
    data class Err(val text: String) : TermLine
    /** Tellyscope talking, dim: notes, refusals, `[exit 1]`. */
    data class Note(val text: UiText) : TermLine
    /** Tellyscope talking, in the error colour: the TV was lost, a confirm question. */
    data class Warn(val text: UiText) : TermLine
}

data class TerminalState(
    val lines: List<TermLine> = emptyList(),
    val cwd: String = "/",
    val running: Boolean = false,
    /** A command that changes the TV, waiting on y/N; the part that matched is [confirmMatch]. */
    val confirming: String? = null,
    val confirmMatch: String? = null,
    /** Lines dropped off the top to keep [MAX_LINES]. */
    val trimmed: Boolean = false,
    /** Newest first, no repeats. */
    val recent: List<String> = emptyList(),
    /** The user typed `exit`: the screen closes. */
    val exited: Boolean = false,
)

/**
 * Advanced's Shell: a terminal on one TV. Holds the lines, the y/N confirm and the recent
 * commands, so they outlive the screen; the session itself is [TvShell], one `sh` on the TV.
 */
class ShellTerminal(private val shell: TvShell, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()
    private var job: Job? = null

    /** Enter on [line]. While a confirm waits, it's the answer: only `y` runs it. */
    fun submit(line: String, online: Boolean) {
        val s = _state.value
        if (s.running) return
        s.confirming?.let { pending ->
            val yes = line.trim().equals("y", ignoreCase = true) || line.trim().equals("yes", ignoreCase = true)
            _state.update { it.copy(confirming = null, confirmMatch = null) }
            print(TermLine.Warn(UiText(R.string.shell_confirm_answered, if (yes) "y" else "N")))
            if (yes && online) start(pending) else print(TermLine.Note(UiText(R.string.shell_not_run)))
            return
        }
        // Enter does nothing while the TV is away: the line stays to run once it's back.
        if (!online) return
        when (val parsed = ShellInput.parse(line)) {
            ShellLine.Empty -> print(TermLine.Typed(s.cwd, ""))
            ShellLine.NotShell -> {
                print(TermLine.Typed(s.cwd, line.trim()))
                print(TermLine.Warn(UiText(R.string.shell_not_shell)))
            }
            is ShellLine.FullScreen -> {
                print(TermLine.Typed(s.cwd, line.trim()))
                print(TermLine.Warn(UiText(R.string.shell_full_screen, parsed.program)))
            }
            is ShellLine.Run -> {
                remember(parsed.command)
                print(TermLine.Typed(s.cwd, parsed.command))
                if (parsed.dropped) print(TermLine.Note(UiText(R.string.shell_dropped_prefix)))
                val match = ShellInput.changesTv(parsed.command)
                if (match != null) {
                    print(TermLine.Note(UiText(R.string.shell_no_history)))
                    _state.update { it.copy(confirming = parsed.command, confirmMatch = match) }
                } else {
                    start(parsed.command)
                }
            }
        }
    }

    /** Shell opened again after `exit` closed it: the next command starts a new session. */
    fun opened() = _state.update { it.copy(exited = false) }

    /** Ctrl-C: stops what's running, or drops the confirm. */
    fun interrupt() {
        when {
            _state.value.running -> shell.stop()
            _state.value.confirming != null -> {
                _state.update { it.copy(confirming = null, confirmMatch = null) }
                print(TermLine.Note(UiText(R.string.shell_not_run)))
            }
        }
    }

    /** Tab on [input]: its last word completed from what's on the TV; several matches are printed. */
    fun complete(input: String, done: (String) -> Unit) {
        if (_state.value.running) return
        val word = input.substringAfterLast(' ')
        val head = input.dropLast(word.length)
        scope.launch {
            val found = mutableListOf<String>()
            shell.run("ls -d -p -- ${quote(word)}* 2>/dev/null") { c -> if (!c.error) found += c.text.lines().filter { it.isNotBlank() } }
            when {
                found.size == 1 -> done(head + found.single().let { if (it.endsWith('/')) it else "$it " })
                found.size > 1 -> {
                    print(TermLine.Typed(_state.value.cwd, input), TermLine.Out(found.joinToString("  ") { it.trimEnd('/').substringAfterLast('/') + if (it.endsWith('/')) "/" else "" }))
                    done(head + found.reduce { a, b -> a.commonPrefixWith(b) })
                }
            }
        }
    }

    fun close() {
        job?.cancel()
        shell.close()
    }

    private fun start(command: String) {
        _state.update { it.copy(running = true) }
        job = scope.launch {
            val out = Pending()
            // Fast output (`logcat`) comes in far more chunks than the screen can draw: print what gathered a few times a second.
            val tick = launch {
                while (true) {
                    delay(PRINT_EVERY_MS)
                    show(out, all = false)
                }
            }
            val end = shell.run(command, out::add)
            tick.cancel()
            show(out, all = true)
            _state.update { it.copy(running = false, cwd = shell.cwd, exited = end == ShellEnd.Exited) }
            when (end) {
                is ShellEnd.Done -> if (end.code != 0) print(TermLine.Note(UiText(R.string.shell_exit_code, end.code)))
                ShellEnd.Stopped -> print(TermLine.Note(UiText(R.string.shell_stopped)))
                ShellEnd.Lost -> print(TermLine.Warn(UiText(R.string.shell_lost)))
                is ShellEnd.Failed -> print(TermLine.Warn(UiText(R.string.shell_failed, end.reason)))
                ShellEnd.Exited -> {}
            }
        }
    }

    private fun show(out: Pending, all: Boolean) {
        val (lines, dropped) = out.take(all)
        if (lines.isNotEmpty()) print(*lines.toTypedArray())
        if (dropped) _state.update { it.copy(trimmed = true) }
    }

    private fun remember(command: String) =
        _state.update { it.copy(recent = (listOf(command) + it.recent.filter { r -> r != command }).take(MAX_RECENT)) }

    private fun print(vararg add: TermLine) = _state.update {
        // One row per line, so the cap counts what's on screen.
        val lines = it.lines + add.flatMap { l ->
            when (l) {
                is TermLine.Out -> l.text.split('\n').map(TermLine::Out)
                is TermLine.Err -> l.text.split('\n').map(TermLine::Err)
                else -> listOf(l)
            }
        }
        if (lines.size > MAX_LINES) it.copy(lines = lines.takeLast(MAX_LINES), trimmed = true) else it.copy(lines = lines)
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        /** Long output (`logcat`) keeps its last 2,000 lines. */
        const val MAX_LINES = 2_000
        const val MAX_RECENT = 8
        /** A line with no end (`cat` of a one-line file) breaks into rows this long, so no single row is too big to draw. */
        const val MAX_WIDTH = 1_000
        const val PRINT_EVERY_MS = 100L
    }
}

/**
 * What a running command printed that isn't on screen yet, in the order it came. Chunks can end
 * mid-line: whole lines are kept, the rest waits for more. Filled from the shell's thread, taken
 * from the screen's.
 */
private class Pending {
    private val held = mapOf(false to StringBuilder(), true to StringBuilder())
    private val lines = ArrayDeque<TermLine>()
    /** Lines dropped before they were shown, past [ShellTerminal.MAX_LINES]. */
    private var dropped = false

    @Synchronized fun add(chunk: ShellChunk) {
        val b = held.getValue(chunk.error).append(chunk.text)
        var start = 0
        while (true) {
            val nl = b.indexOf("\n", start)
            var cut = when {
                nl >= 0 && nl - start <= ShellTerminal.MAX_WIDTH -> nl
                b.length - start > ShellTerminal.MAX_WIDTH -> start + ShellTerminal.MAX_WIDTH
                else -> break
            }
            // Not between the two halves of an emoji.
            if (cut != nl && Character.isHighSurrogate(b[cut - 1])) cut--
            line(b.substring(start, cut), chunk.error)
            start = if (cut == nl) nl + 1 else cut
        }
        b.delete(0, start)
    }

    /** The lines so far, and whether any were dropped; [all] once the command is over, with its unfinished last lines. */
    @Synchronized fun take(all: Boolean): Pair<List<TermLine>, Boolean> {
        if (all) {
            for ((error, b) in held) if (b.isNotEmpty()) line(b.toString(), error)
            held.values.forEach { it.setLength(0) }
        }
        return (lines.toList() to dropped).also {
            lines.clear()
            dropped = false
        }
    }

    private fun line(text: String, error: Boolean) {
        lines.addLast(if (error) TermLine.Err(text) else TermLine.Out(text))
        if (lines.size > ShellTerminal.MAX_LINES) {
            lines.removeFirst()
            dropped = true
        }
    }
}
