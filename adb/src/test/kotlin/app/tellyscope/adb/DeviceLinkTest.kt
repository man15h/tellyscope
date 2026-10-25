// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLinkTest {
    private val reading = Stats(cpuPercent = 10.0, memory = Parsers.Memory(2, 1))

    @Test fun notAllowedStopsRetrying() = runTest {
        var calls = 0
        val link = DeviceLink("tv", 5555, backgroundScope, open = { _, _ -> calls++; ConnectResult.NotAllowed })
        link.connect(); runCurrent()
        assertEquals(DeviceState.NOT_ALLOWED, link.state.value)
        advanceTimeBy(60_000)
        assertEquals(1, calls)
    }

    @Test fun rememberedDeviceStopsAfterThreeNotAllowed() = runTest {
        // Each try shows "Allow debugging?" on the TV again, so a revoked key mustn't loop forever.
        var calls = 0
        val link = DeviceLink("tv", 5555, backgroundScope, open = { _, _ -> calls++; ConnectResult.NotAllowed }, knownGood = true)
        link.connect(); runCurrent()
        advanceTimeBy(60_000)
        assertEquals(DeviceState.NOT_ALLOWED, link.state.value)
        assertEquals(3, calls)
    }

    @Test fun unreachableResetsTheNotAllowedCount() = runTest {
        // A TV waking up: stalls and timeouts interleave, and it keeps retrying until it answers.
        val answers = ArrayDeque(listOf<ConnectResult>(
            ConnectResult.NotAllowed, ConnectResult.NotAllowed, ConnectResult.Unreachable("asleep"),
            ConnectResult.NotAllowed, ConnectResult.NotAllowed,
        ))
        var calls = 0
        val link = DeviceLink("tv", 5555, backgroundScope, knownGood = true,
            open = { _, _ -> calls++; answers.removeFirstOrNull() ?: ConnectResult.Unreachable("off") })
        link.connect(); runCurrent()
        advanceTimeBy(30_001)
        assertEquals(DeviceState.OFFLINE, link.state.value)
        assertTrue(calls > 5)
    }

    @Test fun dropsToOfflineThenReconnects() = runTest {
        val sessions = mutableListOf<FakeShell>()
        var reachable = true
        var failNext = true
        val link = DeviceLink(
            "tv", 5555, backgroundScope,
            monitorFor = { _, _ -> fakeMonitor(failFirst = failNext.also { failNext = false }) },
            open = { _, _ ->
                if (reachable) ConnectResult.Online(FakeShell().also { sessions += it }) else ConnectResult.Unreachable("no route")
            },
        )
        link.connect(); runCurrent()
        // first session's monitor throws after one reading
        assertEquals(DeviceState.OFFLINE, link.state.value)
        assertEquals(reading, link.stats.value) // last reading kept for the greyed page
        assertTrue(sessions[0].closed)

        reachable = false
        advanceTimeBy(5_001)
        assertEquals(DeviceState.OFFLINE, link.state.value)

        reachable = true
        advanceTimeBy(5_001)
        assertEquals(DeviceState.ONLINE, link.state.value)
        assertEquals(2, sessions.size)
    }

    @Test fun needsPairingStopsRetrying() = runTest {
        var calls = 0
        val link = DeviceLink("tv", 37011, backgroundScope, open = { _, _ -> calls++; ConnectResult.NeedsPairing })
        link.connect(); runCurrent()
        advanceTimeBy(60_000)
        assertEquals(DeviceState.NEEDS_PAIRING, link.state.value)
        assertEquals(1, calls)
    }

    @Test fun notAllowedAfterADropStopsAfterThreeTries() = runTest {
        // Online once, then the key is revoked: three tries, then NOT_ALLOWED instead of a prompt every 5 s.
        var calls = 0
        val link = DeviceLink(
            "tv", 5555, backgroundScope,
            monitorFor = { _, _ -> fakeMonitor(failFirst = true) },
            open = { _, _ -> if (calls++ == 0) ConnectResult.Online(FakeShell()) else ConnectResult.NotAllowed },
        )
        link.connect(); runCurrent()
        advanceTimeBy(60_000)
        assertEquals(DeviceState.NOT_ALLOWED, link.state.value)
        assertEquals(1 + 3, calls)
    }

    @Test fun disconnectClosesSessionAndResets() = runTest {
        val shell = FakeShell()
        val link = DeviceLink("tv", 5555, backgroundScope, monitorFor = { _, _ -> fakeMonitor(failFirst = false) },
            open = { _, _ -> ConnectResult.Online(shell) })
        link.connect(); runCurrent()
        assertEquals(DeviceState.ONLINE, link.state.value)
        link.disconnect(); runCurrent()
        assertEquals(DeviceState.FOUND, link.state.value)
        assertTrue(shell.closed)
        assertEquals(null, link.session)
    }

    private fun fakeMonitor(failFirst: Boolean) = object : MonitorSource {
        override fun stats() = flow {
            emit(reading)
            if (failFirst) throw IOException("gone")
            kotlinx.coroutines.awaitCancellation()
        }
    }
}
