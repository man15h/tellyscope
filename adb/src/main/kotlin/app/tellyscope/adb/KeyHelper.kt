// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * The remote's keys, sent by the helper dex (`app/src/helper`, `Keys`) kept running on the TV.
 * `input keyevent` starts a new VM for every key, and that start was the remote's lag; the helper
 * pays it once, on the first key. [dex] is the helper jar bundled in the app's assets.
 *
 * A helper that can't start (push refused, `app_process` blocked by the OEM) or stops answering is
 * not tried again on this [session]: [press] returns null and the caller sends `input keyevent`.
 */
class KeyHelper(val session: AdbSession, private val dex: ByteArray) : AutoCloseable {
    private val lock = Mutex()
    private var pipe: AdbPipe? = null
    private var failed = false

    /**
     * Sends [code] and waits for the TV to take it. True or false is the TV's answer; null means
     * the helper isn't running and the key wasn't sent. Throws if the link is gone.
     */
    suspend fun press(code: Int): Boolean? = send("$code")

    /** Types [text], already checked with [Remote.sendable]; answers like [press]. */
    suspend fun type(text: String): Boolean? = send("t$text")

    /**
     * Moves the media volume [delta] steps in one set, or by one Volume key where a set can't move
     * it (already at the end, or volume held at full: a fixed output or HDMI-CEC). False when the
     * helper couldn't read or set the volume at all, and the keys should go instead.
     */
    suspend fun volume(delta: Int): Boolean? = send("v$delta")

    /** Turns the TV's picture off or on; the TV stays awake and connected. Answers like [press]. */
    suspend fun screen(on: Boolean): Boolean? = send(if (on) "p2" else "p0")

    private suspend fun send(line: String): Boolean? = lock.withLock {
        if (failed) return null
        val p = pipe ?: start() ?: return null
        val answer = try {
            withTimeoutOrNull(ANSWER_TIMEOUT) {
                runInterruptible(Dispatchers.IO) {
                    p.stdin.writeUtf8("$line\n").flush()
                    // A helper that exited answers "", not null: null is the timeout below.
                    p.stdout.readUtf8Line() ?: ""
                }
            }
        } catch (e: CancellationException) {
            // Its answer would be read as the next key's; start a fresh helper next time.
            drop()
            throw e
        } catch (e: Exception) {
            drop()
            failed = true
            // A link that is gone fails the caller's fallback too, so the user still sees it.
            return null
        }
        when (answer) {
            "1" -> true
            "0" -> false
            null -> {
                // Timed out: the key may still land, so sending it again could press it twice.
                drop()
                failed = true
                false
            }
            else -> {
                drop()
                failed = true
                null
            }
        }
    }

    private suspend fun start(): AdbPipe? {
        var p: AdbPipe? = null
        try {
            session.push(dex, PATH)
            val started = session.exec("CLASSPATH=$PATH app_process / $MAIN $PATH 2>/dev/null; rm -f $PATH")
            p = started
            val first = withTimeoutOrNull(START_TIMEOUT) { runInterruptible(Dispatchers.IO) { started.stdout.readUtf8Line() } }
            if (first == READY) return started.also { pipe = it }
        } catch (e: CancellationException) {
            p?.close()
            throw e
        } catch (_: Exception) {
            // Falls through to the fallback.
        }
        p?.close()
        failed = true
        // The shell line removes it too; this covers a run that never got that far.
        withContext(NonCancellable) { runCatching { session.shell("rm -f $PATH") } }
        return null
    }

    private fun drop() {
        pipe?.let { runCatching { it.close() } }
        pipe = null
    }

    /** Ends the helper: its stdin closes and it exits. */
    override fun close() = drop()

    companion object {
        const val PATH = "/data/local/tmp/tellyscope-keys.jar"
        const val MAIN = "app.tellyscope.helper.Keys"
        const val READY = "ready"
        /** A VM start on a slow TV; the label helper starts the same way. */
        val START_TIMEOUT = 15.seconds
        /** Past Android's own 5 s input dispatch timeout, so a key the TV dropped shows as dropped. */
        val ANSWER_TIMEOUT = 6.seconds
    }
}
