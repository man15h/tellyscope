// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.AdbPipe
import app.tellyscope.adb.AdbSession
import app.tellyscope.adb.RemoteEntry
import app.tellyscope.adb.ShellResult
import app.tellyscope.data.AdbDeviceSource
import app.tellyscope.data.ConnectResult
import app.tellyscope.data.DeviceStore
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
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

private const val GUID = "adb-SN1234-a1B2c3"

/** A Google TV on wireless debugging, which keeps its mDNS instance name in a prop. */
private class WirelessTv : AdbSession {
    override suspend fun shell(command: String): ShellResult =
        if (command == "getprop persist.adb.wifi.guid") ShellResult("$GUID\n", 0) else ShellResult("", 0)

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

/** A wireless-debugging TV added by IP:port has no instance name from a scan; it gets the TV's, so its new port can be found. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IpPortServiceTest {
    @Test fun aTvAddedByIpPortKeepsItsInstanceName() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val source = AdbDeviceSource(app, connector = { _, _ -> AdbConnectResult.Online(WirelessTv()) })
        assertTrue(source.connect(ScanHit("tv", 37123, ScanKind.Wireless, null, null)) is ConnectResult.Online)

        val store = DeviceStore(File(app.filesDir, "devices.tsv"))
        val saved = withTimeout(10_000) {
            var d = store.load().firstOrNull { it.host == "tv" }
            while (d?.service == null) {
                delay(20)
                d = store.load().firstOrNull { it.host == "tv" }
            }
            d
        }
        assertEquals(GUID, saved?.service)
        source.forget("tv")
    }
}
