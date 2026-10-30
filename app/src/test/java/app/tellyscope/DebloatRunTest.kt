// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.data.Action
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.ResetApp
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.ui.DISABLING
import app.tellyscope.ui.TellyscopeViewModel
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val KIDS = ResetApp("com.google.android.youtube.tvkids", "YouTube Kids")
private val SPORTS = ResetApp("com.google.android.tvrecommendations.sports", "Sports")
private val FEEDBACK = ResetApp("com.google.android.feedback", "Google Feedback")

/** A TV that turns apps off when asked, except the ones in [refuse], and can hold the next one at [gate]. */
private class RefusingTv : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    val refuse = mutableMapOf<String, String>()
    val asked = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun known() = listOf(Device("tv", ScanHit("tv", 5555, ScanKind.Classic, "tv", null), DeviceState.Online, null, null, null, 0L))

    override fun live(deviceId: String): Flow<Snapshot> = emptyFlow()

    override suspend fun act(deviceId: String, action: Action, packageName: String, undoing: Long?): HistoryEntry {
        gate?.await()
        asked += packageName
        refuse[packageName]?.let { throw IllegalStateException(it) }
        return HistoryEntry(1, deviceId, 0, action, packageName, packageName, Action.Enable, null)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DebloatRunTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val source = RefusingTv()
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.open() {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
    }

    private val run get() = vm.state.value.device.debloat

    @Test fun eachAppIsMarkedAndARefusedOneKeepsItsReason() = with(test) {
        open()
        source.refuse[SPORTS.packageName] = "the TV refused it"
        vm.disableSuggested(listOf(KIDS, SPORTS, FEEDBACK))
        runCurrent()
        val r = run!!
        assertTrue(r.ran)
        assertEquals(3, r.at)
        assertEquals(mapOf(SPORTS.packageName to "the TV refused it"), r.failed.mapValues { it.value.text() })
        assertNull(vm.state.value.device.busy)
        // The result replaces the old snackbar.
        assertNull(vm.notice.value)
    }

    @Test fun theCountMovesAsEachAppAnswers() = with(test) {
        open()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        vm.disableSuggested(listOf(KIDS, SPORTS))
        runCurrent()
        // Debloat switches to progress at once, before the TV has answered for any app.
        assertEquals(0, run!!.at)
        assertFalse(run!!.ran)
        assertEquals(DISABLING, vm.state.value.device.busy)
        source.gate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, run!!.at)
        assertTrue(run!!.ran)
    }

    @Test fun tryAgainRunsOnlyTheRefusedApps() = with(test) {
        open()
        source.refuse[SPORTS.packageName] = "the TV refused it"
        vm.disableSuggested(listOf(KIDS, SPORTS, FEEDBACK))
        runCurrent()
        source.refuse.clear()
        source.asked.clear()
        vm.retryDebloat()
        runCurrent()
        assertEquals(listOf(SPORTS.packageName), source.asked)
        assertEquals(listOf(SPORTS), run!!.apps)
        assertTrue(run!!.failed.isEmpty())
    }

    @Test fun closingMidRunKeepsTheRunAndClosingAfterForgetsIt() = with(test) {
        open()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        vm.disableSuggested(listOf(KIDS))
        runCurrent()
        vm.closeDebloat()
        assertNotNull(run)
        source.gate = null
        gate.complete(Unit)
        runCurrent()
        vm.closeDebloat()
        assertNull(run)
    }
}
