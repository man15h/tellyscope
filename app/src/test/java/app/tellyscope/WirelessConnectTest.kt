// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.data.ConnectResult
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.ui.Copy
import app.tellyscope.ui.RowState
import app.tellyscope.ui.TellyscopeViewModel
import app.tellyscope.ui.WIFI_POLL_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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

/** Every connect answers [result]; null hangs until the caller's timeout. */
private class Answers : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    var result: ConnectResult? = null
    var lan = true

    override fun onLan() = lan

    override suspend fun known() = emptyList<Device>()

    override suspend fun connect(hit: ScanHit): ConnectResult = result ?: awaitCancellation()

    override suspend fun pair(hit: ScanHit, code: String): ConnectResult = result ?: awaitCancellation()
}

@OptIn(ExperimentalCoroutinesApi::class)
class WirelessConnectTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val source = Answers()
    private lateinit var vm: TellyscopeViewModel

    private val wireless = ScanHit("192.0.2.20", 46237, ScanKind.Wireless, "adb-1268-iFvH8u", "Sony")
    private val classic = ScanHit("192.0.2.21", 5555, ScanKind.Classic, null, null)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = TellyscopeViewModel(source, allowTimeoutMs = 60_000, clock = { 0L })
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun connect(hit: ScanHit): RowState? = with(test) {
        vm.connect(hit)
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        vm.state.value.scan.rows[hit.ip]
    }

    @Test fun wirelessTimeoutAsksForTheCode() {
        source.result = null
        assertEquals(RowState.Code(error = Copy.PAIR_INSTEAD), connect(wireless))
    }

    @Test fun wirelessUnreachableAsksForTheCode() {
        source.result = ConnectResult.Unreachable("connection refused")
        assertEquals(RowState.Code(error = Copy.UNREACHABLE, detail = "connection refused"), connect(wireless))
    }

    @Test fun wirelessNotAllowedAsksForTheCode() {
        source.result = ConnectResult.NotAllowed
        assertEquals(RowState.Code(error = Copy.PAIR_INSTEAD), connect(wireless))
    }

    @Test fun wirelessNotAllowedAfterPairingKeepsTheCode() = with(test) {
        source.result = ConnectResult.NeedsPairing
        connect(wireless)
        vm.codeChanged(wireless, "123456")
        source.result = ConnectResult.NotAllowed
        vm.pair(wireless)
        runCurrent()
        assertEquals(RowState.Code("123456", error = Copy.PAIR_INSTEAD), vm.state.value.scan.rows[wireless.ip])
    }

    @Test fun classicTimeoutIsStillTheAllowBox() {
        source.result = null
        assertEquals(RowState.NotAllowed, connect(classic))
    }

    @Test fun classicUnreachableOnWifiIsCantReachNotNoAnswer() {
        source.result = ConnectResult.Unreachable("connection refused")
        assertEquals(RowState.Unreachable(offWifi = false), connect(classic))
    }

    @Test fun wifiDroppedMidConnectIsCantReachOffWifi() {
        source.result = null
        vm.connect(classic)
        test.runCurrent()
        source.lan = false
        test.advanceTimeBy(60_001)
        test.runCurrent()
        assertEquals(RowState.Unreachable(offWifi = true), vm.state.value.scan.rows[classic.ip])
    }

    @Test fun tryAgainOffWifiWaitsThenAsksTheTv() {
        source.lan = false
        vm.connect(classic)
        test.runCurrent()
        assertEquals(RowState.WaitingForWifi, vm.state.value.scan.rows[classic.ip])
        // No Allow countdown while there's no network: the wait has no limit.
        test.advanceTimeBy(120_000)
        test.runCurrent()
        assertEquals(RowState.WaitingForWifi, vm.state.value.scan.rows[classic.ip])
        source.lan = true
        test.advanceTimeBy(WIFI_POLL_MS + 1)
        test.runCurrent()
        assertEquals(RowState.Waiting(0L), vm.state.value.scan.rows[classic.ip])
    }

    @Test fun leavingStopsTheWifiWait() {
        source.lan = false
        vm.connect(classic)
        test.runCurrent()
        vm.leaveConnect(classic)
        source.lan = true
        test.advanceTimeBy(WIFI_POLL_MS + 1)
        test.runCurrent()
        assertEquals(RowState.Unreachable(offWifi = true), vm.state.value.scan.rows[classic.ip])
    }
}
