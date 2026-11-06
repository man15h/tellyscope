// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.AdbPipe
import app.tellyscope.adb.AdbSession
import app.tellyscope.adb.RemoteEntry
import app.tellyscope.adb.ShellResult
import app.tellyscope.data.Action
import app.tellyscope.data.AdbDeviceSource
import app.tellyscope.data.ConnectResult
import app.tellyscope.data.ResetApp
import app.tellyscope.data.ResetPlan
import app.tellyscope.data.ResetRecord
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.Sink
import okio.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import app.tellyscope.adb.ConnectResult as AdbConnectResult

/** A TV that turns on whatever it's asked to. */
private class OnTv : AdbSession {
    val on = mutableListOf<String>()

    override suspend fun shell(command: String): ShellResult = when {
        command.startsWith("pm enable --user 0 ") -> command.removePrefix("pm enable --user 0 ").substringBefore(' ').let {
            on += it
            ShellResult("Package $it new state: enabled\n", 0)
        }
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

/** A reset cancelled part-way, as when the user leaves the app, still leaves History what it turned on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResetCancelTest {
    @Test fun aCancelledResetKeepsWhatItTurnedOn() = runBlocking {
        val tv = OnTv()
        val source = AdbDeviceSource(RuntimeEnvironment.getApplication(), connector = { _, _ -> AdbConnectResult.Online(tv) })
        assertTrue(source.connect(ScanHit("tv", 5555, ScanKind.Classic, "tv", null)) is ConnectResult.Online)
        val apps = listOf("com.acme.one", "com.acme.two", "com.acme.three").map { ResetApp(it, it) }

        val cancelled = runCatching {
            source.reset("tv", ResetPlan(turnOn = apps), null) { if (it.done == 2) throw CancellationException("left the app") }
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertEquals(listOf("com.acme.one", "com.acme.two"), tv.on)

        val reset = source.history("tv").single { it.action == Action.Reset }
        assertEquals(Action.Reset, reset.undo)
        assertEquals(listOf("com.acme.one", "com.acme.two"), ResetRecord.list(reset.previous, ResetRecord.ON))
        source.forget("tv")
    }
}
