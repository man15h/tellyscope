// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ResetApp
import app.tellyscope.data.ResetFailure
import app.tellyscope.data.ResetHome
import app.tellyscope.data.ResetPart
import app.tellyscope.data.ResetPlan
import app.tellyscope.data.ResetProgress
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.ui.RESETTING
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val APPS = listOf("one", "two", "three").map { ResetApp("com.acme.$it", it) }
private val HOME = ResetHome("com.acme.launcher", "Launcher", "com.acme.home", "Home")

/** A TV that resets step by step as the adb one does, Stop checked before each, every step held at [gate] until it opens. */
private class SteppingTv : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    val ran = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun known() = listOf(Device("tv", ScanHit("tv", 5555, ScanKind.Classic, "tv", null), DeviceState.Online, null, null, null, 0L))

    override fun live(deviceId: String): Flow<Snapshot> = emptyFlow()

    override suspend fun resetPlan(deviceId: String) = ResetPlan(turnOn = APPS, home = HOME)

    override suspend fun reset(deviceId: String, plan: ResetPlan, into: Long?, stop: () -> Boolean, onProgress: (ResetProgress) -> Unit): Pair<Long?, List<ResetFailure>> {
        for ((i, app) in plan.turnOn.withIndex()) {
            if (stop()) return 1L to emptyList()
            onProgress(ResetProgress(ResetPart.TurnOn, i, plan.turnOn.size))
            gate?.await()
            ran += app.packageName
        }
        if (plan.home != null && !stop()) {
            onProgress(ResetProgress(ResetPart.Home, 0, 1))
            ran += plan.home!!.to
        }
        return 1L to emptyList()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ResetStopTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val source = SteppingTv()
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private val reset get() = vm.state.value.device.reset!!

    /** Stop during the first app: it finishes, nothing after it starts, and the result keeps what's left. */
    @Test fun stopEndsTheResetAfterTheStepUnderWay() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        vm.openReset()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        vm.runReset()
        runCurrent()
        assertEquals(RESETTING, vm.state.value.device.busy)
        vm.stopReset()
        assertTrue(reset.stopping)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("com.acme.one"), source.ran)
        assertTrue(reset.ran)
        assertEquals(ResetPlan(turnOn = APPS.drop(1), home = HOME), reset.left)
        assertNull(vm.state.value.device.busy)

        // Finish the reset runs just what Stop left, and ends with nothing left.
        source.ran.clear()
        vm.runReset(retry = true)
        runCurrent()
        assertEquals(listOf("com.acme.two", "com.acme.three", HOME.to), source.ran)
        assertNull(reset.left)
    }

    /** Stop tapped before the first step started: nothing ran, so all of it is left. */
    @Test fun stopBeforeTheFirstStepLeavesItAll() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        vm.openReset()
        runCurrent()
        vm.runReset()
        vm.stopReset()
        runCurrent()
        assertEquals(emptyList<String>(), source.ran)
        assertEquals(ResetPlan(turnOn = APPS, home = HOME), reset.left)
    }
}
