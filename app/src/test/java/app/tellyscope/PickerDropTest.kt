// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.AdbPipe
import app.tellyscope.adb.AdbSession
import app.tellyscope.adb.InstalledVersion
import app.tellyscope.adb.RemoteEntry
import app.tellyscope.adb.ShellResult
import app.tellyscope.data.AdbDeviceSource
import app.tellyscope.data.ConnectResult
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** One adb session to the TV; every call throws once [dead], like a socket Android cut in the background. */
private class Link(private val version: Int) : AdbSession {
    @Volatile var dead = false

    override suspend fun shell(command: String): ShellResult = when {
        dead -> throw IOException("link gone")
        command.startsWith("dumpsys package") ->
            ShellResult("Packages:\n  Package [com.example.app] (1a2b3c):\n    versionCode=$version minSdk=21 targetSdk=34\n    versionName=$version.0\n", 0)
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

/** The version check is the first call after the file picker, so it gets a fresh link when the old one died behind it (#290). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickerDropTest {
    @Test fun theVersionCheckAfterThePickerRedialsADeadLink() = runBlocking {
        val before = Link(version = 1)
        val after = Link(version = 2)
        var dials = 0
        val source = AdbDeviceSource(RuntimeEnvironment.getApplication(), connector = { _, _ ->
            AdbConnectResult.Online(if (++dials == 1) before else after)
        })
        val hit = ScanHit("tv", 5555, ScanKind.Classic, "tv", null)
        assertTrue(source.connect(hit) is ConnectResult.Online)

        // The picker is up: the app is in the background, and Android cuts the socket.
        source.pause(true)
        before.dead = true
        source.pause(false)

        val installed = withTimeout(30_000) { source.installed("tv", "com.example.app") }
        assertEquals(InstalledVersion(2, "2.0"), installed)
        assertTrue(dials >= 2)
        source.forget("tv")
    }
}
