// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesTest {
    private val data = ByteArray(300_000) { (it % 251).toByte() }

    @Test fun pushNeverOverwrites() = runTest {
        val sh = FakeShell()
        sh.transfer.files["/sdcard/Download/clip.mp4"] = byteArrayOf(1)
        sh.transfer.files["/sdcard/Download/clip (1).mp4"] = byteArrayOf(2)
        val name = Files(sh).push(Buffer().write(data), data.size.toLong(), "clip.mp4", TvFolder.DOWNLOAD)
        assertEquals("clip (2).mp4", name)
        assertContentEquals(data, sh.transfer.files["/sdcard/Download/clip (2).mp4"])
        assertContentEquals(byteArrayOf(1), sh.transfer.files["/sdcard/Download/clip.mp4"])
    }

    @Test fun pushNeverOverwritesAHiddenFile() = runTest {
        val sh = FakeShell()
        sh.transfer.files["/sdcard/Download/.config"] = byteArrayOf(1)
        val name = Files(sh).push(Buffer().write(data), data.size.toLong(), ".config", TvFolder.DOWNLOAD)
        assertEquals(".config (1)", name)
        assertContentEquals(byteArrayOf(1), sh.transfer.files["/sdcard/Download/.config"])
    }

    @Test fun pushReportsProgressToTheEnd() = runTest {
        val sh = FakeShell()
        val seen = mutableListOf<Progress>()
        Files(sh).push(Buffer().write(data), data.size.toLong(), "a.bin", TvFolder.MOVIES) { seen += it }
        assertEquals(Progress(data.size.toLong(), data.size.toLong()), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b.done > a.done })
    }

    @Test fun pullCopiesTheFile() = runTest {
        val sh = FakeShell()
        sh.transfer.files["/sdcard/Movies/film.mkv"] = data
        val files = Files(sh)
        val f = files.list(TvFolder.MOVIES).single()
        val out = Buffer()
        files.pull(TvFolder.MOVIES, f, out)
        assertContentEquals(data, out.readByteArray())
    }

    @Test fun listShowsPlainVisibleFilesOnly() = runTest {
        val sh = FakeShell()
        sh.transfer.files["/sdcard/Download/a.txt"] = byteArrayOf(1)
        sh.transfer.files["/sdcard/Download/.nomedia"] = byteArrayOf()
        sh.transfer.files["/sdcard/Movies/b.mp4"] = byteArrayOf(1)
        assertEquals(listOf("a.txt"), Files(sh).list(TvFolder.DOWNLOAD).map { it.name })
    }

    @Test fun cancelledPushDeletesThePartialFile() = runBlocking {
        val sh = FakeShell()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var chunks = 0
        sh.transfer.onChunk = { if (++chunks == 3) { started.countDown(); release.await() } }
        val job = async(Dispatchers.Default) {
            Files(sh).push(Buffer().write(data), data.size.toLong(), "big.bin", TvFolder.DOWNLOAD)
        }
        withContext(Dispatchers.IO) { started.await() }
        job.cancel()
        release.countDown()
        assertFailsWith<CancellationException> { job.await() }
        assertFalse("/sdcard/Download/big.bin" in sh.transfer.files)
        assertTrue(sh.ran.contains("rm -f '/sdcard/Download/big.bin'"), "ran: ${sh.ran}")
    }

    @Test fun failedPushDeletesThePartialFile() = runTest {
        val sh = FakeShell()
        var chunks = 0
        sh.transfer.onChunk = { if (++chunks == 3) throw IOException("Sync failed: No space left on device") }
        assertFailsWith<IOException> {
            Files(sh).push(Buffer().write(data), data.size.toLong(), "clip.mp4", TvFolder.DOWNLOAD)
        }
        assertTrue(sh.ran.contains("rm -f '/sdcard/Download/clip.mp4'"), "ran: ${sh.ran}")
    }

    @Test fun finishedPushDeletesNothing() = runTest {
        val sh = FakeShell()
        Files(sh).push(Buffer().write(data), data.size.toLong(), "clip.mp4", TvFolder.DOWNLOAD)
        assertTrue(sh.ran.none { it.startsWith("rm ") })
    }

    @Test fun cancelledPullStopsWriting() = runBlocking {
        val sh = FakeShell()
        sh.transfer.files["/sdcard/Movies/film.mkv"] = data
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var chunks = 0
        sh.transfer.onChunk = { if (++chunks == 2) { started.countDown(); release.await() } }
        val out = Buffer()
        val files = Files(sh)
        val f = files.list(TvFolder.MOVIES).single()
        val job = async(Dispatchers.Default) { files.pull(TvFolder.MOVIES, f, out) }
        withContext(Dispatchers.IO) { started.await() }
        job.cancel()
        release.countDown()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(out.size < data.size)
    }

    @Test fun namesStayInsideTheFolder() {
        assertEquals("_.._etc_passwd", Files.safeName("/../etc/passwd"))
        assertEquals("file", Files.safeName(".."))
        assertEquals("file", Files.safeName("  "))
        assertEquals("it's fine.mp4", Files.safeName("it's fine.mp4"))
    }

    @Test fun uniqueNames() {
        assertEquals("a.mp4", Files.uniqueName("a.mp4", setOf("b.mp4")))
        assertEquals("a (1).mp4", Files.uniqueName("a.mp4", setOf("a.mp4")))
        assertEquals("README (1)", Files.uniqueName("README", setOf("README")))
        assertEquals(".bashrc (1)", Files.uniqueName(".bashrc", setOf(".bashrc")))
    }

    @Test fun quotingSurvivesApostrophes() {
        assertEquals("'it'\\''s.mp4'", shellQuote("it's.mp4"))
    }
}
