// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    @Test fun rememberedDeviceRefusedOnceIsLoggedOut() = runTest {
        // Each try shows "Allow debugging?" on the TV again, so a revoked key gets exactly one.
        var calls = 0
        val link = DeviceLink("tv", 5555, backgroundScope, open = { _, _ -> calls++; ConnectResult.NotAllowed }, knownGood = true)
        link.connect(); runCurrent()
        advanceTimeBy(60_000)
        assertEquals(DeviceState.NOT_ALLOWED, link.state.value)
        assertTrue(link.loggedOutAt.value != null)
        assertEquals(1, calls)
    }

    @Test fun refusalAfterAskingIsNotALogout() = runTest {
        // Connect again, then Deny: the user saw it happen, so it's a plain "not allowed".
        val link = DeviceLink("tv", 5555, backgroundScope, open = { _, _ -> ConnectResult.NotAllowed }, knownGood = true)
        link.connect(asked = true); runCurrent()
        assertEquals(DeviceState.NOT_ALLOWED, link.state.value)
        assertEquals(null, link.loggedOutAt.value)
    }

    @Test fun firstRefusalIsNotALogout() = runTest {
        val link = DeviceLink("tv", 5555, backgroundScope, open = { _, _ -> ConnectResult.NotAllowed })
        link.connect(); runCurrent()
        assertEquals(null, link.loggedOutAt.value)
    }

    @Test fun holdingALogoutNeverConnects() = runTest {
        var calls = 0
        val link = DeviceLink("tv", 5555, backgroundScope, open = { _, _ -> calls++; ConnectResult.NotAllowed }, knownGood = true)
        link.holdLoggedOut(1_000)
        advanceTimeBy(60_000)
        assertEquals(DeviceState.NOT_ALLOWED, link.state.value)
        assertTrue(link.loggedOutAt.value != null)
        assertEquals(0, calls)
    }

    @Test fun pausedLinkDoesNotRetry() = runTest {
        var calls = 0
        val link = DeviceLink("tv", 5555, backgroundScope, open = { _, _ -> calls++; ConnectResult.Unreachable("asleep") })
        link.connect(); runCurrent()
        assertEquals(1, calls)
        link.gate.paused.value = true
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(1, calls)
        link.gate.paused.value = false
        runCurrent()
        assertEquals(2, calls)
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

    @Test fun lanLostGoesOfflineAtOnce() = runTest {
        // The read on the old connection never fails, as on a phone that just lost Wi-Fi.
        val sessions = mutableListOf<FakeShell>()
        var reachable = true
        val link = DeviceLink(
            "tv", 5555, backgroundScope,
            monitorFor = { _, _ -> fakeMonitor(failFirst = false) },
            open = { _, _ ->
                if (reachable) ConnectResult.Online(FakeShell().also { sessions += it }) else ConnectResult.Unreachable("no route")
            },
        )
        link.connect(); runCurrent()
        assertEquals(DeviceState.ONLINE, link.state.value)

        reachable = false
        link.lanLost(); runCurrent()
        assertEquals(DeviceState.OFFLINE, link.state.value)
        assertEquals(reading, link.stats.value)
        assertTrue(sessions[0].closed)

        reachable = true
        advanceTimeBy(5_001)
        assertEquals(DeviceState.ONLINE, link.state.value)
        assertEquals(2, sessions.size)
    }

    @Test fun lanLostLeavesAnOfflineLinkAlone() = runTest {
        var calls = 0
        val link = DeviceLink("tv", 5555, backgroundScope, open = { _, _ -> calls++; ConnectResult.NotAllowed })
        link.connect(); runCurrent()
        link.lanLost()
        advanceTimeBy(60_000)
        assertEquals(DeviceState.NOT_ALLOWED, link.state.value)
        assertEquals(1, calls)
    }

    @Test fun needsPairingStopsRetrying() = runTest {
        var calls = 0
        val link = DeviceLink("tv", 37011, backgroundScope, open = { _, _ -> calls++; ConnectResult.NeedsPairing })
        link.connect(); runCurrent()
        advanceTimeBy(60_000)
        assertEquals(DeviceState.NEEDS_PAIRING, link.state.value)
        assertEquals(1, calls)
    }

    @Test fun refusedAfterADropLogsOutAfterOneTry() = runTest {
        // Online once, then the key is revoked: one try, then a logout instead of a prompt every 5 s.
        var calls = 0
        val link = DeviceLink(
            "tv", 5555, backgroundScope,
            monitorFor = { _, _ -> fakeMonitor(failFirst = true) },
            open = { _, _ -> if (calls++ == 0) ConnectResult.Online(FakeShell()) else ConnectResult.NotAllowed },
        )
        link.connect(); runCurrent()
        advanceTimeBy(60_000)
        assertEquals(DeviceState.NOT_ALLOWED, link.state.value)
        assertTrue(link.loggedOutAt.value != null)
        assertEquals(1 + 1, calls)
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

    @Test fun reconnectLetsTheOldSessionGoBeforeTheNewOneOpens() = runTest {
        // After a revoke the TV keeps the old session up and holds the new one at its prompt.
        val first = FakeShell()
        var opens = 0
        val link = DeviceLink("tv", 5555, backgroundScope, monitorFor = { _, _ -> fakeMonitor(failFirst = false) },
            open = { _, _ -> if (opens++ == 0) ConnectResult.Online(first) else { delay(10_000); ConnectResult.Online(FakeShell()) } })
        link.connect(); runCurrent()
        assertEquals(first, link.session)
        link.connect()
        assertEquals(null, link.session)
        advanceTimeBy(5_000)
        assertTrue(first.closed)
        assertEquals(null, link.session)
        advanceTimeBy(5_001)
        assertTrue(link.session != null && link.session != first)
    }

    @Test fun theOldLoopStoppingLateLeavesTheNewSessionAlone() = runTest {
        // The cancelled loop's cleanup can run after the new one is online, as on a real thread pool (#290).
        val second = FakeShell()
        var opens = 0
        val link = DeviceLink("tv", 5555, backgroundScope,
            monitorFor = { _, _ -> if (opens == 1) slowToStop() else fakeMonitor(failFirst = false) },
            open = { _, _ -> ConnectResult.Online(if (opens++ == 0) FakeShell() else second) })
        link.connect(); runCurrent()
        link.connect(); runCurrent()
        assertEquals(second, link.session)
        advanceTimeBy(1_001)
        assertEquals(second, link.session)
    }

    private fun slowToStop() = MonitorSource {
        flow {
            emit(reading)
            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(1_000) } }
        }
    }

    private fun fakeMonitor(failFirst: Boolean) = object : MonitorSource {
        override fun stats() = flow {
            emit(reading)
            if (failFirst) throw IOException("gone")
            kotlinx.coroutines.awaitCancellation()
        }
    }
}
