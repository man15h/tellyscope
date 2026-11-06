// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.AdbPipe
import app.tellyscope.adb.AdbSession
import app.tellyscope.adb.RemoteEntry
import app.tellyscope.adb.ShellResult
import app.tellyscope.adb.Tweak
import app.tellyscope.adb.Tweaks
import app.tellyscope.data.Action
import app.tellyscope.data.HistoryBook
import app.tellyscope.data.UiTextException
import app.tellyscope.data.applyTweak
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okio.Sink
import okio.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A TV whose DNS never resolves; [writes] counts `settings put` lines, and the ones in [failing] are refused.
 * With [cached], a lookup still answers from the TV's cache, while the network names the provider but never validates it.
 */
private class NoDnsTv(private val failing: Set<Int>, private val cached: Boolean = false) : AdbSession {
    var writes = 0

    override suspend fun shell(command: String): ShellResult = when {
        command.startsWith("dumpsys connectivity") && cached -> ShellResult("NetworkAgentInfo{ lp{{UsePrivateDns: true PrivateDnsServerName: dns.example Domains: null }} }\n", 0)
        command.startsWith("ping") -> if (cached) ShellResult("1 packets transmitted, 1 received", 0) else ShellResult("ping: unknown host", 2)
        command.startsWith("settings get") -> ShellResult("null\noff\n", 0)
        command.startsWith("settings") -> if (++writes in failing) throw IOException("link gone") else ShellResult("", 0)
        else -> ShellResult("", 0)
    }

    override suspend fun push(bytes: ByteArray, remotePath: String) = Unit
    override suspend fun reboot() = Unit
    override suspend fun exec(command: String): AdbPipe = throw IOException("no exec")
    override fun install(source: Source, size: Long, vararg options: String) = Unit
    override fun installMultiple(apks: List<File>, vararg options: String) = Unit
    override fun push(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) = Unit
    override fun pull(sink: Sink, remotePath: String) = Unit
    override fun list(remoteDir: String) = emptyList<RemoteEntry>()
    override fun close() = Unit
}

class DnsRollbackTest {
    private val dns = mapOf("private_dns_specifier" to "dns.example", "private_dns_mode" to "hostname")

    private suspend fun run(tv: NoDnsTv, book: HistoryBook): String = try {
        applyTweak(Tweaks(tv), Tweak.PRIVATE_DNS, dns, strictDns = true) { label, previous ->
            book.record("tv", 0, Action.Change, Tweak.PRIVATE_DNS.name, label.text(), Action.Change, previous = previous)
        }
        error("should have thrown")
    } catch (e: IllegalStateException) {
        (e as UiTextException).text.text()
    }

    @Test fun aProviderThatDoesntResolveIsPutBackAndNotRecorded() = runTest {
        val book = HistoryBook()
        val tv = NoDnsTv(failing = emptySet())
        assertTrue(run(tv, book).contains("was put back"))
        assertEquals(2, tv.writes)
        assertTrue(book.entries("tv").isEmpty())
    }

    /** A lookup the cache answers doesn't pass a provider that never validated. */
    @Test fun aProviderThatNeverValidatesIsPutBackEvenWithTheCacheAnswering() = runTest {
        val book = HistoryBook()
        val tv = NoDnsTv(failing = emptySet(), cached = true)
        assertTrue(run(tv, book).contains("was put back"))
        assertEquals(2, tv.writes)
        assertTrue(book.entries("tv").isEmpty())
    }

    @Test fun aFailedPutBackSaysSoAndLeavesAnUndoInHistory() = runTest {
        val book = HistoryBook()
        val message = run(NoDnsTv(failing = setOf(2)), book)
        assertTrue(message, message.contains("putting the old Private DNS back failed"))
        val entry = book.entries("tv").single()
        assertEquals(Action.Change, entry.undo)
        assertEquals(mapOf("private_dns_specifier" to null, "private_dns_mode" to "off"), entry.previous)
    }
}
