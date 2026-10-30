// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.adb.RemoteKey
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.ui.TellyscopeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** One slow TV: each key waits for [gate] before the TV takes it. */
private class SlowTv : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    val sent = mutableListOf<RemoteKey>()
    var gate = CompletableDeferred<Unit>()

    override suspend fun known() = listOf(
        Device("tv", ScanHit("tv", 5555, ScanKind.Classic, "tv", null), DeviceState.Online, null, null, null, 0L, nickname = "Lounge"),
    )

    override fun live(deviceId: String) = emptyFlow<Snapshot>()

    override suspend fun press(deviceId: String, key: RemoteKey): Boolean {
        gate.await()
        sent += key
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VolumeTapTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val tv = SlowTv()
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun start() = with(test) {
        vm = TellyscopeViewModel(tv, clock = { 0L })
        runCurrent()
    }

    @Test fun tapsBeyondTheOneWaitingAreDropped() = with(test) {
        start()
        repeat(5) { launch { vm.press(RemoteKey.VOLUME_UP) } }
        runCurrent()
        tv.gate.complete(Unit)
        runCurrent()
        // One being sent and one waiting; the other three never queue.
        assertEquals(List(2) { RemoteKey.VOLUME_UP }, tv.sent)
    }

    @Test fun aWaitingTapCancelledWithTheRemoteDoesNotSilenceVolume() = with(test) {
        start()
        launch { vm.press(RemoteKey.VOLUME_UP) }
        val waiting = launch { vm.press(RemoteKey.VOLUME_UP) }
        runCurrent()
        // Leaving the Remote cancels its scope while the second tap waits for the first.
        waiting.cancel()
        tv.gate.complete(Unit)
        runCurrent()
        launch { vm.press(RemoteKey.VOLUME_DOWN) }
        runCurrent()
        assertEquals(listOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN), tv.sent)
    }
}
