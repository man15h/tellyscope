// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.AdbPipe
import app.tellyscope.adb.AdbSession
import app.tellyscope.adb.ConnectResult
import app.tellyscope.adb.RemoteEntry
import app.tellyscope.adb.ShellResult
import app.tellyscope.data.sameTv
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okio.Sink
import okio.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A session that only records whether it was let go. */
private class Tv : AdbSession {
    var closed = false

    override suspend fun shell(command: String) = ShellResult("", 0)
    override suspend fun push(bytes: ByteArray, remotePath: String) = Unit
    override suspend fun reboot() = Unit
    override suspend fun exec(command: String): AdbPipe = throw IOException("no exec")
    override fun install(source: Source, size: Long, vararg options: String) = Unit
    override fun installMultiple(apks: List<File>, vararg options: String) = Unit
    override fun push(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) = Unit
    override fun pull(sink: Sink, remotePath: String) = Unit
    override fun list(remoteDir: String) = emptyList<RemoteEntry>()
    override fun close() { closed = true }
}

class SameTvTest {
    @Test fun theSameTvStaysConnected() = runTest {
        val tv = Tv()
        val r = ConnectResult.Online(tv)
        assertSame(r, sameTv(r, "sn:QV7124ZK") { "sn:QV7124ZK" })
        assertFalse(tv.closed)
    }

    @Test fun anotherTvIsLetGo() = runTest {
        // A neighbour's TV took the address, or the port, after a DHCP change.
        val tv = Tv()
        assertTrue(sameTv(ConnectResult.Online(tv), "sn:QV7124ZK") { "sn:XK03H1234" } is ConnectResult.Unreachable)
        assertTrue(tv.closed)
    }

    @Test fun aTvThatCantBeReadIsLetGo() = runTest {
        val tv = Tv()
        assertTrue(sameTv(ConnectResult.Online(tv), "sn:QV7124ZK") { throw IOException("link gone") } is ConnectResult.Unreachable)
        assertTrue(tv.closed)
    }

    @Test fun aTvNotKnownYetIsNotChecked() = runTest {
        val tv = Tv()
        val r = ConnectResult.Online(tv)
        assertSame(r, sameTv(r, null) { error("not read") })
        assertFalse(tv.closed)
    }

    @Test fun onlyAnOpenSessionIsChecked() = runTest {
        assertSame(ConnectResult.NeedsPairing, sameTv(ConnectResult.NeedsPairing, "sn:QV7124ZK") { error("not read") })
    }
}
