// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvShellTest {

    /** Answers each written command with the frames [reply] gives for it. */
    private class FakeChannel(val reply: (String) -> List<ShellPacket>) : ShellChannel {
        val written = mutableListOf<String>()
        val frames = LinkedBlockingQueue<ShellPacket>()
        var closed = false

        override fun read(): ShellPacket {
            if (closed) throw IOException("closed")
            return frames.take().also { if (it === CLOSED) throw IOException("closed") }
        }

        override fun write(bytes: ByteArray) {
            val s = String(bytes)
            written += s
            frames.addAll(reply(s))
        }

        override fun close() {
            closed = true
            frames.add(CLOSED)
        }

        companion object {
            val CLOSED = ShellPacket.Exit(-1)
        }
    }

    private fun out(s: String) = ShellPacket.Out(s.toByteArray())
    private fun err(s: String) = ShellPacket.Err(s.toByteArray())
    private fun marker(code: Int, dir: String) = out("\u001ETELLYSCOPE $code $dir\n")

    @Test fun outputErrorsAndExitStatusComeApart() = runBlocking {
        val ch = FakeChannel { listOf(out("one\n"), err("bad\n"), out("two\n"), marker(1, "/")) }
        val printed = mutableListOf<ShellChunk>()
        val end = TvShell(FakeShell()) { ch }.run("ls") { printed += it }
        assertEquals(ShellEnd.Done(1), end)
        assertEquals(listOf(ShellChunk("one\n", false), ShellChunk("bad\n", true), ShellChunk("two\n", false)), printed)
    }

    @Test fun cdCarriesOverToTheNextCommand() = runBlocking {
        val ch = FakeChannel { listOf(marker(0, "/sdcard")) }
        val sh = TvShell(FakeShell()) { ch }
        sh.run("cd /sdcard") {}
        assertEquals("/sdcard", sh.cwd)
    }

    @Test fun aMarkerSplitAcrossFramesIsNotPrinted() = runBlocking {
        val ch = FakeChannel { listOf(out("hi\n\u001ETELLY"), out("SCOPE 0 /\n")) }
        val printed = StringBuilder()
        assertEquals(ShellEnd.Done(0), TvShell(FakeShell()) { ch }.run("echo hi") { printed.append(it.text) })
        assertEquals("hi\n", printed.toString())
    }

    @Test fun exitEndsTheSession() = runBlocking {
        val ch = FakeChannel { listOf(ShellPacket.Exit(0)) }
        assertEquals(ShellEnd.Exited, TvShell(FakeShell()) { ch }.run("exit") {})
        // Closed off the caller's thread.
        repeat(100) { if (!ch.closed) Thread.sleep(10) }
        assertTrue(ch.closed)
    }

    @Test fun stopEndsTheCommandAndTheNextShellStartsWhereItWas() = runBlocking {
        val first = FakeChannel { listOf(marker(0, "/data/local/tmp")) }
        val second = FakeChannel { listOf(marker(0, "/data/local/tmp")) }
        val channels = ArrayDeque(listOf(first, second))
        val sh = TvShell(FakeShell()) { channels.removeFirst() }
        sh.run("cd /data/local/tmp") {}
        val logcat = FakeChannel { emptyList() }
        channels.addFirst(logcat)
        sh.stop() // nothing running: closes `first`
        val running = Thread { Thread.sleep(200); sh.stop() }.apply { start() }
        assertEquals(ShellEnd.Stopped, sh.run("logcat") {})
        running.join()
        sh.run("ls") {}
        assertTrue(second.written.first().startsWith("cd '/data/local/tmp' 2>/dev/null\n{ ls\n}"))
    }

    @Test fun aTvWithoutShellV2RunsEachCommandOnItsOwn() = runBlocking {
        val tv = FakeShell().apply { answer = { ShellResult("listing\n\u001ETELLYSCOPE 2 /system\n", 0) } }
        val printed = StringBuilder()
        val sh = TvShell(tv) { null }
        assertEquals(ShellEnd.Done(2), sh.run("ls") { printed.append(it.text) })
        assertEquals("listing\n", printed.toString())
        assertEquals("/system", sh.cwd)
        assertTrue(tv.ran.single().startsWith("cd '/' 2>/dev/null; { ls\n}"))
    }

    @Test fun aCharacterCutBetweenFramesPrintsWhole() = runBlocking {
        val bytes = "テレビ\n".toByteArray()
        val ch = FakeChannel { listOf(ShellPacket.Out(bytes.copyOfRange(0, 4)), ShellPacket.Out(bytes.copyOfRange(4, bytes.size)), marker(0, "/")) }
        val printed = StringBuilder()
        TvShell(FakeShell()) { ch }.run("echo") { printed.append(it.text) }
        assertEquals("テレビ\n", printed.toString())
    }

    @Test fun aLostTvSaysSo() = runBlocking {
        val sh = TvShell(FakeShell()) { throw IOException("connection closed") }
        assertEquals(ShellEnd.Lost, sh.run("ls") {})
    }

    @Test fun anAdbErrorEndsTheCommandInsteadOfThrowing() = runBlocking {
        val bad = object : ShellChannel {
            var closed = false
            override fun read(): ShellPacket = throw IllegalStateException("bad packet")
            override fun write(bytes: ByteArray) {}
            override fun close() { closed = true }
        }
        val good = FakeChannel { listOf(out("hi\n"), marker(0, "/")) }
        val channels = ArrayDeque(listOf(bad, good))
        val sh = TvShell(FakeShell()) { channels.removeFirst() }
        assertEquals(ShellEnd.Failed("bad packet"), sh.run("ls") {})
        // The broken shell is dropped: the next command opens a new one.
        assertEquals(ShellEnd.Done(0), sh.run("echo hi") {})
        repeat(100) { if (!bad.closed) Thread.sleep(10) }
        assertTrue(bad.closed)
    }

    /** Works until [dead], then fails the way a shell on a dropped link does; [answered] frames come back first. */
    private class DyingChannel(val reply: (String) -> List<ShellPacket>, val answered: Int = 0) : ShellChannel {
        val written = mutableListOf<String>()
        private val frames = ArrayDeque<ShellPacket>()
        var dead = false
        private var left = 0

        override fun read(): ShellPacket {
            if (dead && left-- <= 0) throw IllegalStateException("closed")
            return frames.removeFirst()
        }

        override fun write(bytes: ByteArray) {
            written += String(bytes)
            if (dead && answered == 0) throw IllegalStateException("closed")
            frames.addAll(reply(String(bytes)))
            if (dead) left = answered
        }

        override fun close() {}
    }

    @Test fun aShellThatDiedInTheBackgroundRunsTheCommandInANewOne() = runBlocking {
        val first = DyingChannel({ listOf(marker(0, "/sdcard")) })
        val second = FakeChannel { listOf(out("uid=2000\n"), marker(0, "/sdcard")) }
        val channels = ArrayDeque(listOf<ShellChannel>(first, second))
        val sh = TvShell(FakeShell()) { channels.removeFirst() }
        sh.run("cd /sdcard") {}
        first.dead = true
        val printed = StringBuilder()
        assertEquals(ShellEnd.Done(0), sh.run("id") { printed.append(it.text) })
        assertEquals("uid=2000\n", printed.toString())
        assertTrue(second.written.single().startsWith("cd '/sdcard' 2>/dev/null\n{ id\n}"))
    }

    @Test fun aShellThatDiesAfterTheTvAnsweredDoesNotRunItAgain() = runBlocking {
        val first = DyingChannel({ listOf(out("package:a\n"), marker(0, "/")) }, answered = 1)
        val second = FakeChannel { listOf(marker(0, "/")) }
        val channels = ArrayDeque(listOf<ShellChannel>(first, second))
        val sh = TvShell(FakeShell()) { channels.removeFirst() }
        sh.run("true") {}
        first.dead = true
        assertEquals(ShellEnd.Failed("closed"), sh.run("pm list packages") {})
        assertTrue(second.written.isEmpty())
    }

    @Test fun anAdbErrorOpeningTheShellSaysWhat() = runBlocking {
        val sh = TvShell(FakeShell()) { throw IllegalStateException() }
        assertEquals(ShellEnd.Failed("IllegalStateException"), sh.run("ls") {})
    }

    @Test fun pastedAdbShellIsDropped() {
        assertEquals(ShellLine.Run("pm list packages -d", dropped = true), ShellInput.parse("adb shell pm list packages -d"))
        assertEquals(ShellLine.Run("getprop", dropped = true), ShellInput.parse("  adb -s 192.0.2.1:5555 shell getprop"))
        assertEquals(ShellLine.Run("ls", dropped = false), ShellInput.parse("ls"))
        assertEquals(ShellLine.Empty, ShellInput.parse("adb shell"))
    }

    @Test fun otherAdbCommandsAreRefused() {
        assertEquals(ShellLine.NotShell, ShellInput.parse("adb install kodi.apk"))
        assertEquals(ShellLine.NotShell, ShellInput.parse("adb connect 192.0.2.1"))
    }

    @Test fun fullScreenProgramsAreRefused() {
        assertEquals(ShellLine.FullScreen("top"), ShellInput.parse("top"))
        assertEquals(ShellLine.FullScreen("vi"), ShellInput.parse("cd /sdcard && vi notes.txt"))
        assertTrue(ShellInput.parse("top -n 1") is ShellLine.Run)
    }

    @Test fun commandsThatChangeTheTvAreFound() {
        assertEquals("pm uninstall", ShellInput.changesTv("pm uninstall --user 0 com.google.android.tvrecommendations"))
        assertEquals("pm disable-user", ShellInput.changesTv("pm disable-user com.example"))
        assertEquals("settings put", ShellInput.changesTv("settings put global animator_duration_scale 0"))
        assertEquals("wm density", ShellInput.changesTv("wm density 200"))
        assertEquals("rm", ShellInput.changesTv("cd /sdcard; rm -rf Download"))
        assertEquals("pm clear", ShellInput.changesTv("su -c 'pm clear com.example'"))
        assertNull(ShellInput.changesTv("pm list packages -d"))
        assertNull(ShellInput.changesTv("settings get global adb_enabled"))
        assertNull(ShellInput.changesTv("am force-stop com.example"))
        assertNull(ShellInput.changesTv("rmdir empty"))
        assertNull(ShellInput.changesTv("wm size"))
    }

    @Test fun fullPathsAndShDashCAreSeenThrough() {
        assertEquals("pm uninstall", ShellInput.changesTv("/system/bin/pm uninstall com.example"))
        assertEquals("pm uninstall", ShellInput.changesTv("sh -c 'pm uninstall com.example'"))
        assertEquals("rm", ShellInput.changesTv("sh -c \"/system/bin/rm -rf /sdcard/Download\""))
        assertEquals("cmd package clear", ShellInput.changesTv("/system/bin/cmd package clear com.example"))
        assertNull(ShellInput.changesTv("sh -c 'pm list packages'"))
    }

    @Test fun wifiOffAndHidingAppsAreFound() {
        assertEquals("svc wifi disable", ShellInput.changesTv("svc wifi disable"))
        assertEquals("cmd wifi set-wifi-enabled disabled", ShellInput.changesTv("cmd wifi set-wifi-enabled disabled"))
        assertEquals("pm hide", ShellInput.changesTv("pm hide com.example"))
        assertEquals("pm suspend", ShellInput.changesTv("pm suspend com.example"))
        assertEquals("pm set-home-activity", ShellInput.changesTv("pm set-home-activity com.example/.Home"))
        assertEquals("cmd package hide", ShellInput.changesTv("cmd package hide com.example"))
        assertEquals("cmd package suspend", ShellInput.changesTv("cmd package suspend com.example"))
        assertEquals("cmd package set-home-activity", ShellInput.changesTv("cmd package set-home-activity com.example/.Home"))
        assertNull(ShellInput.changesTv("svc wifi enable"))
        assertNull(ShellInput.changesTv("cmd wifi set-wifi-enabled enabled"))
        assertNull(ShellInput.changesTv("cmd wifi status"))
    }
}
