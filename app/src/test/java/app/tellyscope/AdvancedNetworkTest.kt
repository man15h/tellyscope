// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.adb.StatsProfile
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.NetworkInfo
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.ui.TellyscopeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Two online TVs; every network read is counted by the TV it went to. */
private class NetworkReads : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    val reads = mutableListOf<String>()

    override suspend fun known() = listOf("tv-a", "tv-b").map {
        Device(it, ScanHit(it, 5555, ScanKind.Classic, it, null), DeviceState.Online, null, null, null, 0L)
    }

    override fun live(deviceId: String) = emptyFlow<Snapshot>()
    override suspend fun network(deviceId: String) = NetworkInfo("wlan0", deviceId, null, null, null, null, emptyList(), emptyList()).also { reads += deviceId }
}

/** Only Advanced shows the network, so only an open Advanced reads it: fresh on open, and for the TV on screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedNetworkTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val tv = NetworkReads()
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun theOtherPagesNeverReadTheNetwork() = with(test) {
        vm = TellyscopeViewModel(tv, clock = { 0L })
        runCurrent()
        assertEquals("tv-a", vm.state.value.selectedId)
        vm.showStats(StatsProfile.OVERVIEW)
        wait(60_000)
        vm.showStats(StatsProfile.APPS)
        wait(60_000)
        assertEquals(emptyList<String>(), tv.reads)
    }

    @Test fun advancedReadsOnOpenAndStopsWhenItCloses() = with(test) {
        vm = TellyscopeViewModel(tv, clock = { 0L })
        wait(60_000)
        vm.showStats(StatsProfile.ADVANCED)
        runCurrent()
        assertEquals(listOf("tv-a"), tv.reads) // fresh, without waiting for a tick
        wait(30_000)
        assertEquals(4, tv.reads.size) // and every 10 s while open
        vm.showStats(StatsProfile.OTHER)
        tv.reads.clear()
        wait(60_000)
        assertEquals(emptyList<String>(), tv.reads)
        vm.showStats(StatsProfile.ADVANCED) // back again: fresh again
        runCurrent()
        assertEquals(listOf("tv-a"), tv.reads)
    }

    @Test fun aSwitchReadsTheNewTvAndLeavesTheOldOne() = with(test) {
        vm = TellyscopeViewModel(tv, clock = { 0L })
        runCurrent()
        vm.showStats(StatsProfile.ADVANCED)
        runCurrent()
        tv.reads.clear()
        vm.select("tv-b")
        runCurrent()
        assertEquals(listOf("tv-b"), tv.reads)
        wait(60_000)
        assertEquals(emptyList<String>(), tv.reads.filter { it == "tv-a" })
        assertEquals("tv-b", vm.state.value.device.network?.ip)
    }
}
