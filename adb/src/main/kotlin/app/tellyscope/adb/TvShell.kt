// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** One frame of a shell-v2 stream: the TV's stdout, its stderr, or the shell's exit. */
sealed interface ShellPacket {
    class Out(val bytes: ByteArray) : ShellPacket
    class Err(val bytes: ByteArray) : ShellPacket
    data class Exit(val code: Int) : ShellPacket
}

/** A `sh` on the TV with stdin open, speaking shell v2 so stderr and the exit status come apart. Blocking; faked in tests. */
interface ShellChannel : AutoCloseable {
    fun read(): ShellPacket
    fun write(bytes: ByteArray)
}

/** What a [TvShell] prints: the TV's own output, or its error output. */
data class ShellChunk(val text: String, val error: Boolean)

sealed interface ShellEnd {
    /** The command finished with [code]. */
    data class Done(val code: Int) : ShellEnd
    /** [TvShell.stop] ended it. */
    data object Stopped : ShellEnd
    /** The user typed `exit`: the session on the TV is over. */
    data object Exited : ShellEnd
    /** The TV went away mid-command. */
    data object Lost : ShellEnd
}

/**
 * One shell session on the TV, for the Shell terminal: a single `sh` that each command is written
 * into, so `cd` and variables carry over like a real terminal. After every command a marker line
 * on stdout brings back its exit status and the new working directory.
 *
 * No terminal sits in between (raw, not a pty), so full-screen programs can't run and there's no
 * Ctrl-C to send: [stop] closes the shell instead, and the next command opens a new one in the
 * same directory. A TV without shell v2 (Android 6 and older) runs each command on its own through
 * [shell], `cd`'d to the last directory, with its error output mixed into the rest.
 */
class TvShell(
    private val shell: AdbShell,
    private val open: suspend () -> ShellChannel?,
) {
    /** The directory the next command runs in. */
    var cwd: String = "/"
        private set

    @Volatile private var channel: ShellChannel? = null
    @Volatile private var stopped = false
    /** A new shell starts in /; the first command into it goes back to [cwd] first. */
    private var fresh = false
    /** One command at a time: Tab's lookup and Enter share the shell. */
    private val turn = Mutex()

    suspend fun run(command: String, print: (ShellChunk) -> Unit): ShellEnd = turn.withLock {
        stopped = false
        val ch = try {
            channel ?: open()?.also { opened ->
                channel = opened
                fresh = true
            }
        } catch (e: IOException) {
            return ShellEnd.Lost
        } ?: return oneShot(command, print)
        // Stopped while the shell was still opening: nothing ran.
        if (stopped) {
            drop(ch)
            return ShellEnd.Stopped
        }
        return try {
            runInterruptible(Dispatchers.IO) { converse(ch, command, print) }
        } catch (e: IOException) {
            drop(ch)
            if (stopped) ShellEnd.Stopped else ShellEnd.Lost
        }
    }

    /** Ends the running command by closing its shell; [run] returns [ShellEnd.Stopped]. Safe from the main thread. */
    fun stop() {
        stopped = true
        channel?.let(::drop)
    }

    fun close() = stop()

    private fun drop(ch: ShellChannel) {
        if (channel === ch) channel = null
        // Closing tells adbd over the socket, so not on the caller's thread, which may be the main one.
        Thread { runCatching { ch.close() } }.start()
    }

    private fun converse(ch: ShellChannel, command: String, print: (ShellChunk) -> Unit): ShellEnd {
        val back = if (fresh && cwd != "/") "cd ${shellQuote(cwd)} 2>/dev/null\n" else ""
        fresh = false
        // Braces, not a subshell, so `cd` stays; stdin from /dev/null so a command can't eat the marker line.
        ch.write("$back{ $command\n} </dev/null\nprintf '$MARK%s %s\\n' \"\$?\" \"\$PWD\"\n".toByteArray())
        val out = StringBuilder()
        val outText = Utf8()
        val errText = Utf8()
        while (true) {
            when (val p = ch.read()) {
                is ShellPacket.Err -> errText.decode(p.bytes).takeIf { it.isNotEmpty() }?.let { print(ShellChunk(it, error = true)) }
                is ShellPacket.Exit -> {
                    if (out.isNotEmpty()) print(ShellChunk(out.toString(), error = false))
                    drop(ch)
                    return ShellEnd.Exited
                }
                is ShellPacket.Out -> {
                    out.append(outText.decode(p.bytes))
                    val at = out.indexOf(MARK)
                    if (at < 0) {
                        // A marker split across two frames: hold its start back until the rest comes.
                        val held = out.lastIndexOf(MARK[0]).takeIf { it >= 0 && MARK.startsWith(out.substring(it)) } ?: out.length
                        if (held > 0) print(ShellChunk(out.substring(0, held), error = false))
                        out.delete(0, held)
                        continue
                    }
                    if (at > 0) {
                        print(ShellChunk(out.substring(0, at), error = false))
                        out.delete(0, at)
                    }
                    val nl = out.indexOf("\n")
                    if (nl < 0) continue
                    marker(out.substring(MARK.length, nl))?.let { (code, dir) ->
                        cwd = dir
                        return ShellEnd.Done(code)
                    }
                    out.delete(0, nl + 1)
                }
            }
        }
    }

    private suspend fun oneShot(command: String, print: (ShellChunk) -> Unit): ShellEnd {
        val r = try {
            shell.shell("cd ${shellQuote(cwd)} 2>/dev/null; { $command\n} </dev/null 2>&1; printf '$MARK%s %s\\n' \"\$?\" \"\$PWD\"")
        } catch (e: IOException) {
            return ShellEnd.Lost
        }
        val at = r.output.lastIndexOf(MARK)
        val body = if (at >= 0) r.output.substring(0, at) else r.output
        if (body.isNotEmpty()) print(ShellChunk(body, error = false))
        val (code, dir) = (if (at >= 0) marker(r.output.substring(at + MARK.length).trimEnd()) else null) ?: return ShellEnd.Done(r.exitCode)
        cwd = dir
        return ShellEnd.Done(code)
    }

    private fun marker(s: String): Pair<Int, String>? {
        val code = s.substringBefore(' ').toIntOrNull() ?: return null
        return code to s.substringAfter(' ', "/").ifEmpty { "/" }
    }

    /** UTF-8 across frames: a character cut between two frames waits for its last bytes instead of printing as �. */
    private class Utf8 {
        private var held = ByteArray(0)

        fun decode(bytes: ByteArray): String {
            val all = held + bytes
            // Back from the end to the start of the last character; keep it if it isn't all here yet.
            var lead = all.size - 1
            while (lead >= 0 && lead >= all.size - 4 && all[lead].toInt() and 0xC0 == 0x80) lead--
            val need = if (lead < 0) 0 else when (all[lead].toInt() and 0xFF) {
                in 0xF0..0xF7 -> 4
                in 0xE0..0xEF -> 3
                in 0xC0..0xDF -> 2
                else -> 1
            }
            val cut = if (lead >= 0 && all.size - lead < need) lead else all.size
            held = all.copyOfRange(cut, all.size)
            return String(all, 0, cut, Charsets.UTF_8)
        }
    }

    private companion object {
        /** ASCII record separator: no command's output starts a line with it. */
        const val MARK = "\u001ETELLYSCOPE "
    }
}
