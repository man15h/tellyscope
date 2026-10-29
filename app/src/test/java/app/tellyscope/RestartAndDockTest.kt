// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.Docked
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.RemoteButtons
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.ui.Copy
import app.tellyscope.ui.Restart
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One TV called Projector, whose state, apps and dock the test sets; launches are recorded. */
private class Tv : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    var state = DeviceState.Online
    var apps = emptyList<AppInfo>()
    var dock = emptyList<Docked>()
    var opens = true
    val launched = mutableListOf<String>()
    val acted = mutableListOf<String>()

    override suspend fun known() = listOf(
        Device("tv", ScanHit("tv", 5555, ScanKind.Classic, "tv", null), state, null, null, null, 0L, nickname = "Projector"),
    )

    override fun live(deviceId: String) = emptyFlow<Snapshot>()
    override suspend fun apps(deviceId: String) = apps
    override suspend fun reboot(deviceId: String) = true
    override suspend fun dock(deviceId: String) = dock
    override suspend fun setDock(deviceId: String, dock: List<Docked>) { this.dock = dock }

    override suspend fun launch(deviceId: String, packageName: String): Boolean {
        launched += packageName
        return opens
    }

    override suspend fun act(deviceId: String, action: Action, packageName: String, undoing: Long?): HistoryEntry {
        acted += packageName
        return HistoryEntry(1, deviceId, 0, action, packageName, packageName, null, null)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RestartAndDockTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val tv = Tv()
    private var now = 0L
    private lateinit var vm: TellyscopeViewModel

    private fun app(pkg: String, label: String, enabled: Boolean = true) = AppInfo(pkg, label, null, false, enabled, null, "", launchable = true)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun start() = with(test) {
        vm = TellyscopeViewModel(tv, clock = { now })
        runCurrent()
    }

    @Test fun aRestartWaitsAMinuteThenIsLateThenAfterRetryIsLost() {
        val r = Restart(since = 0, down = true)
        assertEquals(Restart.Phase.Waiting, r.phase(59_999))
        assertEquals(Restart.Phase.Late, r.phase(60_000))
        val retried = r.copy(waitFrom = 70_000, retried = true)
        assertEquals(Restart.Phase.Waiting, retried.phase(129_999))
        assertEquals(Restart.Phase.Lost, retried.phase(130_000))
        // Still on after a minute: the TV ignored it, and that's said elsewhere.
        assertEquals(Restart.Phase.Waiting, Restart(since = 0).phase(60_000))
    }

    @Test fun aTvBackFromARestartSaysSoByName() = with(test) {
        start()
        vm.reboot()
        runCurrent()
        tv.state = DeviceState.Offline
        advanceTimeBy(3_100)
        assertTrue(vm.state.value.restarting.getValue("tv").down)
        tv.state = DeviceState.Online
        advanceTimeBy(3_100)
        assertNull(vm.state.value.restarting["tv"])
        assertEquals(Copy.backOnline("Projector"), vm.notice.value)
    }

    @Test fun retryWaitsAnotherMinuteAndCloseStopsWaiting() = with(test) {
        start()
        vm.reboot()
        runCurrent()
        tv.state = DeviceState.Offline
        now = 61_000
        advanceTimeBy(3_100)
        assertEquals(Restart.Phase.Late, vm.state.value.restarting.getValue("tv").phase(now))
        vm.retryRestart()
        assertEquals(Restart.Phase.Waiting, vm.state.value.restarting.getValue("tv").phase(now))
        now = 122_000
        assertEquals(Restart.Phase.Lost, vm.state.value.restarting.getValue("tv").phase(now))
        vm.stopWaiting()
        assertNull(vm.state.value.restarting["tv"])
    }

    @Test fun anAppUninstalledOnTheTvLeavesTheDock() = with(test) {
        assertFalse(RemoteButtons.ANY_SERVICE)
        tv.dock = listOf(Docked("com.youtube", "YouTube"), Docked("org.smarttube", "SmartTube"))
        tv.apps = listOf(app("com.youtube", "YouTube"))
        start()
        assertEquals(listOf("YouTube"), vm.state.value.device.dock.map { it.label })
        assertEquals(listOf("YouTube"), tv.dock.map { it.label })
        assertEquals(Copy.leftDock(listOf("SmartTube")), vm.notice.value)
    }

    @Test fun aDockTapOpensTheAppWithoutHistory() = with(test) {
        start()
        vm.launch(Docked("com.youtube", "YouTube"))
        runCurrent()
        assertEquals(listOf("com.youtube"), tv.launched)
        assertEquals(emptyList<String>(), tv.acted)
        tv.opens = false
        vm.launch(Docked("com.kodi", "Kodi"))
        runCurrent()
        assertEquals(Copy.cantOpen("Kodi"), vm.notice.value)
    }

    @Test fun aButtonForAnAppNotOnTheTvSaysSoWithoutTrying() = with(test) {
        tv.apps = listOf(app("com.youtube", "YouTube"))
        start()
        vm.launch(Docked("com.netflix.ninja", "Netflix"))
        runCurrent()
        assertEquals(Copy.notOnTv("Netflix"), vm.notice.value)
        assertEquals(emptyList<String>(), tv.launched)
    }

    @Test fun theDockIsSavedPerTv() = with(test) {
        start()
        vm.setDock(listOf(Docked("com.youtube", "YouTube")))
        runCurrent()
        assertEquals(listOf("com.youtube"), tv.dock.map { it.packageName })
        assertEquals(listOf("com.youtube"), vm.state.value.device.dock.map { it.packageName })
    }
}
