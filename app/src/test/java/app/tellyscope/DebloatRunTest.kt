// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.adb.ConnectionRequest
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
import kotlinx.coroutines.test.advanceTimeBy
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

/**
 * TVs ([tvs]) that turn apps off when asked, except the ones in [refuse], and can hold the next one at [gate].
 * Each shows [request] until it is answered.
 */
private class RefusingTv : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    val refuse = mutableMapOf<String, String>()
    val asked = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var tvs = listOf("tv")
    var request: ConnectionRequest? = null

    override suspend fun known() = tvs.map { Device(it, ScanHit(it, 5555, ScanKind.Classic, it, null), DeviceState.Online, null, null, null, 0L) }

    override suspend fun connectionRequest(deviceId: String) = request

    override suspend fun answerRequest(deviceId: String, request: ConnectionRequest, allow: Boolean, always: Boolean): Boolean {
        this.request = null
        return true
    }

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

    /** Debloat's progress and its hold on the TV outlast a look at another TV, and picking the same one again. */
    @Test fun aRunSurvivesSwitchingTvsAndRefusesASecond() = with(test) {
        source.tvs = listOf("tv", "tv2")
        open()
        val first = CompletableDeferred<Unit>()
        source.gate = first
        vm.disableSuggested(listOf(KIDS, SPORTS))
        runCurrent()

        vm.select("tv")
        runCurrent()
        assertEquals(DISABLING, vm.state.value.device.busy)
        assertEquals(0, run!!.at)

        vm.select("tv2")
        runCurrent()
        assertNull(vm.state.value.device.busy)
        assertNull(run)
        // KIDS answers while the other TV is on screen.
        val second = CompletableDeferred<Unit>()
        source.gate = second
        first.complete(Unit)
        runCurrent()

        vm.select("tv")
        runCurrent()
        assertEquals(DISABLING, vm.state.value.device.busy)
        assertEquals(1, run!!.at)
        assertEquals(listOf(KIDS, SPORTS), run!!.apps)
        // A second Debloat or a Reset is refused while it runs.
        vm.disableSuggested(listOf(FEEDBACK))
        vm.openReset()
        advanceTimeBy(1_000)
        runCurrent()
        vm.runReset()
        runCurrent()
        assertEquals(listOf(KIDS, SPORTS), run!!.apps)
        assertNull(vm.state.value.device.reset?.running)

        source.gate = null
        second.complete(Unit)
        runCurrent()
        assertEquals(2, run!!.at)
        assertTrue(run!!.ran)
        assertNull(vm.state.value.device.busy)
        assertEquals(listOf(KIDS.packageName, SPORTS.packageName), source.asked)
    }

    /** Answering an Allow prompt mid-run leaves the run in charge of the TV. */
    @Test fun answeringAPromptMidRunKeepsTheRun() = with(test) {
        source.request = ConnectionRequest("AA:BB")
        open()
        assertEquals("AA:BB", vm.state.value.device.request?.fingerprint)
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        vm.disableSuggested(listOf(KIDS, SPORTS))
        runCurrent()

        vm.answerRequest(allow = true, always = false)
        runCurrent()
        assertNull(vm.state.value.device.request)
        assertFalse(vm.state.value.device.answering)
        assertEquals(DISABLING, vm.state.value.device.busy)
        vm.disableSuggested(listOf(FEEDBACK))
        assertEquals(listOf(KIDS, SPORTS), run!!.apps)

        source.gate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, run!!.at)
        assertNull(vm.state.value.device.busy)
    }
}
