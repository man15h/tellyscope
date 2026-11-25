// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.AdbDeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.DeviceStore
import app.tellyscope.data.LastKnown
import app.tellyscope.data.SavedDevice
import app.tellyscope.data.ScanKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.YearMonth

/** What a TV last reported survives an app restart while it's off, and goes with Forget. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LastKnownTest {
    private val context = RuntimeEnvironment.getApplication()
    private val file = File(context.filesDir, "devices.tsv")
    private val facts = LastKnown("XGIMI", "XGIMI", "11", YearMonth.of(2023, 5), 9.4, 12.0)

    // Port 1 on loopback refuses at once: the TV is off for the whole test.
    private val tv = SavedDevice("127.0.0.1", 1, ScanKind.Classic, "xgimi-projector", "XGIMI Horizon Pro", 1_760_000_000_000, "sn:XK03H1234", facts)

    @Test fun offlineTvKeepsItsFactsAcrossARestartUntilForget() = runBlocking {
        // What the last launch saved.
        DeviceStore(file).save(listOf(tv))

        val source = AdbDeviceSource(context, fetch = { throw IOException("offline $it") })
        val device = source.known().single()
        assertEquals(DeviceState.Offline, device.state)
        assertEquals(facts, device.lastKnown)
        assertEquals("XGIMI", device.manufacturer)
        assertEquals("XGIMI Horizon Pro", device.model)
        assertEquals("11", device.android)
        assertEquals(1_760_000_000_000, device.lastSeen)

        source.forget(device.id)
        // The file is written off the caller's thread.
        withTimeout(5_000) { while (DeviceStore(file).load().isNotEmpty()) delay(20) }
        assertEquals(emptyList<Any>(), AdbDeviceSource(context, fetch = { throw IOException("offline $it") }).known())
    }
}
