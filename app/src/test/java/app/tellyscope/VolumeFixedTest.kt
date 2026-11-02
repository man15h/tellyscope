// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.ui.TellyscopeViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One TV; [fixed] is its answer to the volume read, and null makes the read throw (the TV is gone). */
private class Box : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    var fixed: Boolean? = true

    override suspend fun known() = listOf(
        Device("tv", ScanHit("tv", 5555, ScanKind.Classic, "tv", null), DeviceState.Online, null, null, null, 0L, nickname = "Fire TV"),
    )

    override fun live(deviceId: String) = emptyFlow<Snapshot>()

    override suspend fun volumeFixed(deviceId: String) = fixed ?: throw IOException("gone")
}

@OptIn(ExperimentalCoroutinesApi::class)
class VolumeFixedTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val tv = Box()
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun check() = with(test) {
        vm.checkVolume()
        runCurrent()
    }

    @Test fun aFixedVolumeBoxIsMarkedAndUnmarkedOnTheNextRead() = with(test) {
        vm = TellyscopeViewModel(tv, clock = { 0L })
        runCurrent()
        check()
        assertEquals(setOf("tv"), vm.state.value.volumeFixed)
        // Plugged into another TV, or its own speaker: the keys work again.
        tv.fixed = false
        check()
        assertTrue(vm.state.value.volumeFixed.isEmpty())
    }

    @Test fun aReadThatFailsLeavesTheLastAnswer() = with(test) {
        vm = TellyscopeViewModel(tv, clock = { 0L })
        runCurrent()
        check()
        tv.fixed = null
        check()
        assertEquals(setOf("tv"), vm.state.value.volumeFixed)
    }
}
