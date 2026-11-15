// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceList
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ListGroup
import app.tellyscope.data.ListPackage
import app.tellyscope.data.ResetApp
import app.tellyscope.data.ResetFailure
import app.tellyscope.data.ResetPlan
import app.tellyscope.data.ResetProgress
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.data.Suggestions
import app.tellyscope.data.suggest
import app.tellyscope.ui.Copy
import app.tellyscope.ui.TellyscopeViewModel
import app.tellyscope.ui.debloatState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

private const val JUNK = "com.acme.junk"

/** A debloated TV: [JUNK], on its list, is off until Reset turns it back on. */
private class DebloatedTv : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    var on = false
    /** App reads that throw before one answers, as a TV busy after Reset can. */
    var failReads = 0
    private val list = DeviceList("tv", "TV", null, 30, emptySet(), listOf(ListGroup("Junk", listOf(ListPackage(JUNK, null, false)))))

    override suspend fun known() = listOf(Device("tv", ScanHit("tv", 5555, ScanKind.Classic, "tv", null), DeviceState.Online, null, null, null, 0L))

    override fun live(deviceId: String): Flow<Snapshot> = emptyFlow()

    override suspend fun apps(deviceId: String): List<AppInfo> {
        if (failReads > 0) {
            failReads--
            throw IllegalStateException("the TV didn't answer")
        }
        return listOf(AppInfo(JUNK, "Junk", null, true, on, null, ""))
    }

    override suspend fun suggestions(deviceId: String, apps: suspend () -> List<AppInfo>): Suggestions =
        Suggestions.Ready(null, list, 30, suggest(list, apps(), 30), emptyList())

    override suspend fun resetPlan(deviceId: String) = ResetPlan(turnOn = listOf(ResetApp(JUNK, "Junk")))

    override suspend fun reset(deviceId: String, plan: ResetPlan, into: Long?, stop: () -> Boolean, onProgress: (ResetProgress) -> Unit): Pair<Long?, List<ResetFailure>> {
        on = true
        return 1L to emptyList()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ResetReloadTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val source = DebloatedTv()
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.reset() {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        assertEquals(0, debloatState(vm.state.value.device).count)
        vm.openReset()
        runCurrent()
        vm.runReset()
        runCurrent()
    }

    @Test fun anAppResetTurnedOnIsReadyToDebloatAgain() = with(test) {
        reset()
        val page = vm.state.value.device
        assertEquals(1, debloatState(page).count)
        // The Debloat sheet lists it too: the list is worked out again from the new app list.
        assertEquals(listOf(JUNK), (page.lists as Suggestions.Ready).groups.flatMap { g -> g.packages.map { it.pkg } })
    }

    @Test fun aReadThatFailsOnceIsTriedAgain() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        vm.openReset()
        runCurrent()
        source.failReads = 1
        vm.runReset()
        runCurrent()
        assertEquals(1, debloatState(vm.state.value.device).count)
        assertNull(vm.notice.value)
    }

    @Test fun aReadThatKeepsFailingSaysSo() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        vm.openReset()
        runCurrent()
        source.failReads = 2
        vm.runReset()
        runCurrent()
        assertEquals(Copy.RESET_APPS_STALE, vm.notice.value)
    }
}
