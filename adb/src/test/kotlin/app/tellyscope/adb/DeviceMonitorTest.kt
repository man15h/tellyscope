// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceMonitorTest {
    private val sep = "\n@@tellyscope@@\n"

    /** Each FAST call advances the CPU counters by busy +25 / total +100. */
    private fun device(): AdbShell {
        var tick = 0L
        return AdbShell { cmd ->
            if (cmd.startsWith("cat /proc/stat")) {
                tick++
                ok("cpu  ${tick * 25} 0 0 ${tick * 75} 0 0 0 0\n${sep}MemTotal: 2000 kB\nMemAvailable: 500 kB\n")
            } else {
                ok("[ro.product.model]: [XK03H]\n[ro.build.version.sdk]: [30]\n$sep" +
                    "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/x 16000 9800 6200 62% /data\n$sep" +
                    "300.5 100.0\n")
            }
        }
    }

    @Test fun firstReadingHasEverything() = runTest {
        val s = DeviceMonitor(device()).stats().first()
        assertEquals(25.0, s.cpuPercent)
        assertEquals(Parsers.Memory(2000, 500), s.memory)
        assertEquals(Parsers.Storage(16000, 9800), s.storage)
        assertEquals(300L, s.uptimeSeconds)
        assertEquals("XK03H", s.model)
        assertEquals(30, s.sdk)
    }

    @Test fun networkSpeedIsBytesPerSecondBetweenFastReadings() = runTest {
        var tick = 0L
        val tv = AdbShell { cmd ->
            if (cmd.startsWith("cat /proc/stat")) {
                tick++
                // 4000 bytes in and 1000 out per reading on wlan0; loopback moves too but isn't counted.
                ok("cpu  0 0 0 0\n${sep}MemTotal: 2000 kB\n$sep" +
                    "Inter-|   Receive |  Transmit\n face |bytes packets errs drop fifo frame compressed multicast|bytes packets\n" +
                    "    lo: ${tick * 999} 1 0 0 0 0 0 0 ${tick * 999} 1 0 0 0 0 0 0\n" +
                    " wlan0: ${tick * 4000} 1 0 0 0 0 0 0 ${tick * 1000} 1 0 0 0 0 0 0\n")
            } else ok("")
        }
        val s = DeviceMonitor(tv, clock = testScheduler.timeSource).stats().first()
        assertEquals(2500L, s.netBytesPerSec) // 5000 bytes over the default 2 s
    }

    @Test fun keepsEmitting() = runTest {
        assertEquals(3, DeviceMonitor(device()).stats().take(3).toList().size)
    }

    @Test fun silentDeviceThrowsIo() = runTest {
        val hung = AdbShell { kotlinx.coroutines.awaitCancellation() }
        assertFailsWith<IOException> { DeviceMonitor(hung).stats().first() }
    }

    @Test fun processesCarryTheirPackageAndScreenState() = runTest {
        val tv = AdbShell { cmd ->
            when {
                cmd.startsWith("cat /proc/stat") -> ok("cpu  25 0 0 75 0 0 0 0\n${sep}MemTotal: 2000 kB\nMemAvailable: 500 kB\n")
                cmd.startsWith("top") -> ok(
                    "PID USER RES %CPU NAME\n2201 u0_a87 312M 3.5 com.google.android.youtube.tv\n512 system 120M 9.0 system_server\n" +
                        "$sep  mWakefulness=Asleep\n",
                )
                else -> ok("$sep$sep${sep}package:com.google.android.youtube.tv uid:10087\n")
            }
        }
        val s = DeviceMonitor(tv).stats().first()
        assertEquals(
            listOf(
                AppProcess(2201, "com.google.android.youtube.tv", "com.google.android.youtube.tv", 3.5, 312 * 1024),
                AppProcess(512, "system_server", null, 9.0, 120 * 1024),
            ),
            s.processes,
        )
        assertEquals(false, s.screenOn)
        assertEquals(mapOf(10087 to listOf("com.google.android.youtube.tv")), s.packages)
    }

    private companion object {
        const val SEP = "\n@@tellyscope@@\n"
    }

    /** Answers the fast read and `echo`, logs every command, and hangs on `top` until cancelled. */
    private class SlowTop(val onTop: () -> Unit = {}) : AdbShell {
        val ran = mutableListOf<String>()

        override suspend fun shell(command: String): ShellResult {
            ran += command
            if (command.startsWith("top")) { onTop(); awaitCancellation() }
            return if (command.startsWith("cat /proc/stat")) ok("cpu  25 0 0 75 0 0 0 0\n@@tellyscope@@\nMemTotal: 2000 kB\n") else ok("")
        }
    }

    @Test fun aSlowReadOnlySkipsItsSample() = runTest {
        // A TV busy changing Home: `top` runs past the timeout, but the TV still answers, so it isn't lost.
        val tv = SlowTop()
        val readings = DeviceMonitor(tv).stats().take(2).toList()
        assertEquals(2, readings.size)
        assertEquals(emptyList<AppProcess>(), readings.last().processes)
        assertTrue("echo" in tv.ran)
    }

    @Test fun statsWaitWhileAChangeRuns() = runTest {
        val gate = StatsGate()
        val topStarted = CompletableDeferred<Unit>()
        val tv = SlowTop { topStarted.complete(Unit) }
        val readings = mutableListOf<Stats>()
        val collecting = backgroundScope.launch { DeviceMonitor(tv, gate = gate).stats().collect { readings += it } }
        topStarted.await()

        // A change starts while the process read is still out, as in the reported capture.
        val done = CompletableDeferred<Unit>()
        launch { gate.during { done.await() } }
        runCurrent()
        val before = tv.ran.size
        advanceTimeBy(60_000)
        val during = tv.ran.drop(before)
        assertTrue(during.isNotEmpty() && during.all { it == "echo" }, "ran during the change: $during")
        assertTrue(collecting.isActive)
        assertEquals(emptyList<Stats>(), readings)

        done.complete(Unit)
        advanceTimeBy(5_000)
        assertTrue(readings.isNotEmpty())
    }

    @Test fun nothingIsReadWhilePaused() = runTest {
        var calls = 0
        val inner = device()
        val tv = AdbShell { cmd -> calls++; inner.shell(cmd) }
        val gate = StatsGate().apply { paused.value = true }
        val readings = mutableListOf<Stats>()
        backgroundScope.launch { DeviceMonitor(tv, gate = gate).stats().collect { readings += it } }
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(0, calls)
        gate.paused.value = false
        advanceTimeBy(5_000); runCurrent()
        assertTrue(calls > 0)
        assertTrue(readings.isNotEmpty())
    }

    @Test fun aTvThatDiesMidChangeIsStillLost() = runTest {
        val gate = StatsGate()
        val ran = mutableListOf<String>()
        val dead = AdbShell { cmd -> ran += cmd; awaitCancellation() }
        backgroundScope.launch { gate.during { awaitCancellation() } }
        runCurrent()
        assertFailsWith<IOException> { DeviceMonitor(dead, gate = gate).stats().first() }
        assertEquals(listOf("echo"), ran)
    }

    @Test fun otherScreensSkipTheProcessRead() = runTest {
        val gate = StatsGate().apply { profile.value = StatsProfile.OTHER }
        val tv = SlowTop()
        val s = DeviceMonitor(tv, gate = gate).stats().first()
        assertEquals(emptyList<AppProcess>(), s.processes)
        assertTrue(tv.ran.none { it.startsWith("top") || it.startsWith("ps") }, "ran: ${tv.ran}")
        assertTrue("echo" !in tv.ran)
    }

    /** Answers `ps -A -o` with [ps], plain `ps` with [plain]; `top` and `dumpsys` are logged and answered empty. */
    private class PsTv(var ps: String, val plain: String = "") : AdbShell {
        val ran = mutableListOf<String>()

        override suspend fun shell(command: String): ShellResult {
            ran += command
            return when {
                command.startsWith("cat /proc/stat") -> ok("cpu  25 0 0 75 0 0 0 0\n@@tellyscope@@\nMemTotal: 2000 kB\n")
                command.startsWith("ps -A") -> ok(ps)
                command == "ps" -> ok(plain)
                command.startsWith("getprop") -> ok("${SEP.repeat(3)}package:com.google.android.youtube.tv uid:10087\n")
                else -> ok("")
            }
        }
    }

    @Test fun lightLevelReadsMemoryWithoutTop() = runTest {
        val gate = StatsGate().apply { profile.value = StatsProfile.OVERVIEW }
        val tv = PsTv("PID USER RSS NAME\n2201 u0_a87 319488 com.google.android.youtube.tv\n512 system 122880 system_server\n")
        val s = DeviceMonitor(tv, gate = gate).stats().take(3).toList().last()
        assertEquals(
            listOf(
                AppProcess(2201, "com.google.android.youtube.tv", "com.google.android.youtube.tv", null, 319488),
                AppProcess(512, "system_server", null, null, 122880),
            ),
            s.processes,
        )
        assertEquals(null, s.screenOn)
        assertTrue(tv.ran.none { it.startsWith("top") || it.startsWith("dumpsys") }, "ran: ${tv.ran}")
    }

    @Test fun lightLevelFallsBackToPlainPs() = runTest {
        // A ps that ignores -o: only its header comes back, so plain ps is read instead.
        val gate = StatsGate().apply { profile.value = StatsProfile.OVERVIEW }
        val tv = PsTv(
            ps = "USER PID PPID VSIZE RSS WCHAN PC NAME\n",
            plain = "USER PID PPID VSIZE RSS WCHAN PC NAME\nu0_a87 2201 300 1200000 319488 ffffffff 00000000 S com.google.android.youtube.tv\n",
        )
        val s = DeviceMonitor(tv, gate = gate).stats().first()
        assertEquals(listOf(AppProcess(2201, "com.google.android.youtube.tv", "com.google.android.youtube.tv", null, 319488)), s.processes)
        assertTrue("ps" in tv.ran)
    }

    @Test fun aBlankPsReplyDoesNotSwitchToPlainPs() = runTest {
        val gate = StatsGate().apply { profile.value = StatsProfile.OVERVIEW }
        val tv = PsTv("")
        val readings = mutableListOf<Stats>()
        backgroundScope.launch { DeviceMonitor(tv, gate = gate, clock = testScheduler.timeSource).stats().collect { readings += it } }
        runCurrent()
        tv.ps = "PID USER RSS NAME\n2201 u0_a87 319488 com.google.android.youtube.tv\n"
        advanceTimeBy(2_001)
        assertEquals(listOf(2201), readings.last().processes.map { it.pid })
        assertTrue("ps" !in tv.ran, "ran: ${tv.ran}")
    }

    @Test fun openingAdvancedReadsTheFullListAtOnce() = runTest {
        // From Apps, whose round is 10 s: the switch doesn't wait that out.
        val gate = StatsGate().apply { profile.value = StatsProfile.APPS }
        val tv = PsTv("PID USER RSS NAME\n")
        backgroundScope.launch { DeviceMonitor(tv, gate = gate, clock = testScheduler.timeSource).stats().collect {} }
        runCurrent()
        assertTrue(tv.ran.none { it.startsWith("top") })
        gate.profile.value = StatsProfile.ADVANCED
        runCurrent()
        assertTrue(tv.ran.any { it.startsWith("top") }, "ran: ${tv.ran}")
    }

    @Test fun anAppStoppedOnAppsLeavesTheListAtOnce() = runTest {
        // Apps reads processes every 10 s: a stopped app must not linger in Running until the next one.
        val gate = StatsGate().apply { profile.value = StatsProfile.APPS }
        val tv = PsTv("PID USER RSS NAME\n2201 u0_a87 319488 com.google.android.youtube.tv\n")
        val readings = mutableListOf<Stats>()
        backgroundScope.launch { DeviceMonitor(tv, gate = gate, clock = testScheduler.timeSource).stats().collect { readings += it } }
        advanceTimeBy(10_001)
        assertEquals(listOf(2201), readings.last().processes.map { it.pid })

        advanceTimeBy(1_000)
        gate.during(reread = true) { tv.ps = "PID USER RSS NAME\n" }
        runCurrent()
        assertEquals(emptyList(), readings.last().processes)
    }

    @Test fun aDebloatIsReadAgainOnceAtTheEnd() = runTest {
        val gate = StatsGate().apply { profile.value = StatsProfile.APPS }
        val tv = PsTv("PID USER RSS NAME\n")
        backgroundScope.launch { DeviceMonitor(tv, gate = gate, clock = testScheduler.timeSource).stats().collect {} }
        advanceTimeBy(1_000)
        val before = tv.ran.size
        // Five apps turned off one by one, each its own change inside the batch.
        gate.during(reread = true) { repeat(5) { gate.during(reread = true) { delay(500) } } }
        runCurrent()
        val after = tv.ran.drop(before)
        assertEquals(1, after.count { it.startsWith("getprop") }, "ran: $after")
        assertEquals(1, after.count { it.startsWith("ps") }, "ran: $after")
    }

    @Test fun twoSeparateChangesAreReadTwice() = runTest {
        val gate = StatsGate().apply { profile.value = StatsProfile.APPS }
        val tv = PsTv("PID USER RSS NAME\n")
        backgroundScope.launch { DeviceMonitor(tv, gate = gate, clock = testScheduler.timeSource).stats().collect {} }
        advanceTimeBy(1_000)
        val before = tv.ran.size
        repeat(2) {
            gate.during(reread = true) {}
            advanceTimeBy(50)
        }
        val after = tv.ran.drop(before)
        assertEquals(2, after.count { it.startsWith("getprop") }, "ran: $after")
        assertEquals(2, after.count { it.startsWith("ps") }, "ran: $after")
    }

    @Test fun clearingCachesShowsTheFreedStorageAtOnce() = runTest {
        // Storage is read once a minute; on Files or Tweaks, which read no processes, a change still brings it.
        val gate = StatsGate().apply { profile.value = StatsProfile.OTHER }
        var used = 9800
        val tv = AdbShell { cmd ->
            if (cmd.startsWith("cat /proc/stat")) ok("cpu  25 0 0 75 0 0 0 0\n${sep}MemTotal: 2000 kB\n")
            else ok("$sep" + "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/x 16000 $used 0 0% /data\n$sep$sep$sep")
        }
        val readings = mutableListOf<Stats>()
        backgroundScope.launch { DeviceMonitor(tv, gate = gate, clock = testScheduler.timeSource).stats().collect { readings += it } }
        advanceTimeBy(10_001)
        assertEquals(Parsers.Storage(16000, 9800), readings.last().storage)

        gate.during(reread = true) { used = 7000 }
        runCurrent()
        assertEquals(Parsers.Storage(16000, 7000), readings.last().storage)
    }

    @Test fun aKeyPressDoesNotReadProcessesAgain() = runTest {
        val gate = StatsGate().apply { profile.value = StatsProfile.APPS }
        val tv = PsTv("PID USER RSS NAME\n")
        backgroundScope.launch { DeviceMonitor(tv, gate = gate, clock = testScheduler.timeSource).stats().collect {} }
        advanceTimeBy(11_000)
        val before = tv.ran.count { it.startsWith("ps") }
        gate.during {}
        runCurrent()
        assertEquals(before, tv.ran.count { it.startsWith("ps") })
    }

    /** Commands each screen's profile runs in its first 20 s: (fast reads, ps, top). */
    private fun TestScope.reads(profile: StatsProfile): Triple<Int, Int, Int> {
        val gate = StatsGate().apply { this.profile.value = profile }
        val tv = PsTv("PID USER RSS NAME\n2201 u0_a87 319488 com.google.android.youtube.tv\n")
        backgroundScope.launch { DeviceMonitor(tv, gate = gate, clock = testScheduler.timeSource).stats().collect {} }
        advanceTimeBy(20_001)
        return Triple(
            tv.ran.count { it.startsWith("cat /proc/stat") },
            tv.ran.count { it.startsWith("ps") },
            tv.ran.count { it.startsWith("top") },
        )
    }

    @Test fun overviewReadsStripAndMemoryEvery2s() = runTest { assertEquals(Triple(11, 11, 0), reads(StatsProfile.OVERVIEW)) }

    @Test fun appsReadsStripAndMemoryEvery10s() = runTest { assertEquals(Triple(3, 3, 0), reads(StatsProfile.APPS)) }

    @Test fun advancedReadsStripAndTopEvery2s() = runTest { assertEquals(Triple(11, 0, 11), reads(StatsProfile.ADVANCED)) }

    @Test fun otherScreensReadOnlyTheStripEvery10s() = runTest { assertEquals(Triple(3, 0, 0), reads(StatsProfile.OTHER)) }

    @Test fun aTopSlowerThanTheRoundReadsBackToBackWithoutOverlap() = runTest {
        var inFlight = 0
        var most = 0
        var tops = 0
        val tv = AdbShell { cmd ->
            inFlight++
            most = maxOf(most, inFlight)
            try {
                if (cmd.startsWith("top")) { tops++; delay(3_000) }
                if (cmd.startsWith("cat /proc/stat")) ok("cpu  25 0 0 75 0 0 0 0${SEP}MemTotal: 2000 kB\n") else ok("")
            } finally {
                inFlight--
            }
        }
        val gate = StatsGate().apply { profile.value = StatsProfile.ADVANCED }
        backgroundScope.launch { DeviceMonitor(tv, gate = gate, clock = testScheduler.timeSource).stats().collect {} }
        advanceTimeBy(12_001)
        assertEquals(1, most)
        assertEquals(5, tops) // at 0, 3, 6, 9 and 12 s: one after another, none queued up
    }
}
