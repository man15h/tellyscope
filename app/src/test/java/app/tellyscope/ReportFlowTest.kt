// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.adb.AppFacts
import app.tellyscope.adb.Reports
import app.tellyscope.adb.TvFacts
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.Docked
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.ui.Copy
import app.tellyscope.ui.ReportKind
import app.tellyscope.ui.TellyscopeViewModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One TV called Projector on wireless debugging; its state and its read are set by the test. */
private class ReportTv : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    var state = DeviceState.Online
    var readable = true
    val facts = TvFacts("XGIMI", "XGIMI Horizon Pro", "XK03H", "11", 30, "RTT1", listOf("com.xgimi.ota"), 3, emptySet())

    override suspend fun known() = listOf(
        Device("tv", ScanHit("tv", 5555, ScanKind.Wireless, "tv", null), state, null, null, null, 0L, nickname = "Projector"),
    )

    override fun live(deviceId: String) = emptyFlow<Snapshot>()
    // No app list read yet, so a launch goes to the TV and fails there.
    override suspend fun apps(deviceId: String) = emptyList<AppInfo>()
    override suspend fun launch(deviceId: String, packageName: String) = false
    override suspend fun facts(deviceId: String) = if (readable) facts else throw java.io.IOException("gone")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReportFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val tv = ReportTv()
    private val app = AppFacts("0.8.0", "Pixel 8, Android 16")
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun start() = with(test) {
        vm = TellyscopeViewModel(tv, clock = { 0L }, appFacts = app)
        runCurrent()
    }

    @Test fun aReportReadsTheTvAndKnowsHowItConnected() = with(test) {
        start()
        vm.openReport(ReportKind.Problem)
        assertTrue(vm.state.value.report!!.reading)
        assertEquals(Reports.Connection.WIRELESS_DEBUGGING, vm.state.value.report!!.connection)
        runCurrent()
        val r = vm.state.value.report!!
        assertFalse(r.reading)
        assertEquals(tv.facts, r.tv)
        assertEquals(app, r.app)
    }

    @Test fun theLogHoldsWhatTheAppSaidThenWhatTheTvSaid() = with(test) {
        start()
        vm.launch(Docked("org.xbmc.kodi", "Kodi"))
        runCurrent()
        vm.openReport(ReportKind.Problem, "Tried to install Kodi", said = "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        val r = vm.state.value.report!!
        assertEquals("Tried to install Kodi", r.details)
        assertEquals(listOf("00:00:00 " + Copy.cantOpen("Kodi").text(), "The TV said: INSTALL_FAILED_UPDATE_INCOMPATIBLE"), r.log!!.text().lines().map { it.replaceFirst(Regex("^\\d\\d:\\d\\d:\\d\\d"), "00:00:00") })
    }

    @Test fun withNoTvOnlineItSaysItCouldNotConnect() = with(test) {
        tv.state = DeviceState.Offline
        start()
        vm.openReport(ReportKind.Problem)
        runCurrent()
        val r = vm.state.value.report!!
        assertEquals(Reports.Connection.COULD_NOT_CONNECT, r.connection)
        assertNull(r.tv)
        assertFalse(r.reading)
    }

    @Test fun aTvThatWentAwayLeavesTheReportWithoutIt() = with(test) {
        tv.readable = false
        start()
        vm.openReport(ReportKind.NewTv)
        runCurrent()
        assertNull(vm.state.value.report!!.tv)
        assertFalse(vm.state.value.report!!.reading)
    }

    @Test fun aReportThatFitClosesOneThatDidntWaitsForTheRest() = with(test) {
        start()
        vm.openReport(ReportKind.NewTv)
        runCurrent()
        val whole = Reports.newTv(app, tv.facts, Reports.Stage.HERE_IS_MY_LIST, "")
        vm.reportOpened(whole)
        assertNull(vm.state.value.report)

        vm.openReport(ReportKind.NewTv)
        runCurrent()
        val big = tv.facts.copy(stockPackages = (1..600).map { "com.example.stock$it" })
        val cut = Reports.newTv(app, big, Reports.Stage.HERE_IS_MY_LIST, "")
        assertNotNull(cut.rest)
        vm.reportOpened(cut)
        assertEquals(cut, vm.state.value.report!!.posted)
        vm.closeReport()
        assertNull(vm.state.value.report)
    }
}
