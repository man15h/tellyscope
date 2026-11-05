// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ScanEvent
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.ui.TellyscopeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private fun tv(ip: String) = Device(ip, ScanHit(ip, 5555, ScanKind.Classic, null, "Amazon"), DeviceState.Online, "Fire TV Stick 4K", "Amazon", "9", 0L)

/** Remembered TVs, and a LAN where every scan finds them all. */
private class SavedTvs(vararg ips: String) : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    private val saved = ips.map(::tv).toMutableList()
    private val lan = saved.map { it.hit }
    var scans = 0

    override suspend fun known() = saved.toList()

    override suspend fun forget(deviceId: String) {
        saved.removeAll { it.id == deviceId }
    }

    override fun scan() = flow {
        scans++
        lan.forEach { emit(ScanEvent.Found(it)) }
        emit(ScanEvent.Done)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ForgetRescanTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun forgettingTheLastTvScansAndFindsItAgain() = with(test) {
        val source = SavedTvs("tv-a")
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        // The opening scan skips the saved TV and stops, as a TV is on screen.
        assertEquals(1, source.scans)
        assertEquals(emptyList<String>(), vm.state.value.scan.hits.map { it.ip })

        vm.forget("tv-a")
        runCurrent()
        assertNull(vm.state.value.selectedId)
        assertEquals(2, source.scans)
        assertEquals(listOf("tv-a"), vm.state.value.scan.hits.map { it.ip })
    }

    @Test fun forgettingOneOfTwoTvsDoesNotScan() = with(test) {
        val source = SavedTvs("tv-a", "tv-b")
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        assertEquals(1, source.scans)

        vm.forget("tv-a")
        runCurrent()
        assertEquals("tv-b", vm.state.value.selectedId)
        assertEquals(1, source.scans)
    }
}
