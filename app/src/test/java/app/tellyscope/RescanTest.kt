// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ScanEvent
import app.tellyscope.ui.RESCAN_EVERY_MS
import app.tellyscope.ui.ScanScreen
import app.tellyscope.ui.ScanUi
import app.tellyscope.ui.TellyscopeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A LAN with no TV on it: every scan ends empty. */
private class NoTv : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    var scans = 0

    override suspend fun known() = emptyList<Device>()

    override fun scan() = flowOf<ScanEvent>(ScanEvent.Done).also { scans++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RescanTest {
    @get:Rule val compose = createComposeRule()

    /** The scan screen on its last step, Find your TV. */
    private fun show(scan: ScanUi, now: Long = 0L, onRescan: () -> Unit = {}) {
        compose.setContent { ScanScreen(scan, now, onRescan, {}, { _, _ -> }, {}, { null }) }
        repeat(2) { compose.onNodeWithText("Done, next").performScrollTo().performClick() }
    }

    @Test fun idleCountsDownToTheNextScanAndRescans() {
        var rescans = 0
        show(ScanUi(nextScanAt = 7_000), now = 600) { rescans++ }
        compose.onNodeWithText("No TV yet. Looking again in 7 s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rescan").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, rescans)
    }

    @Test fun rescanWaitsOutARunningScan() {
        show(ScanUi(scanning = true))
        compose.onNodeWithText("Looking for your TV…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rescan").performScrollTo().assertIsNotEnabled()
    }

    @Test fun rescanCutsTheWaitShort() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val source = NoTv()
        val vm = TellyscopeViewModel(source, clock = { 0L })
        try {
            with(TestScope(dispatcher)) {
                runCurrent()
                assertEquals(1, source.scans)
                assertEquals(RESCAN_EVERY_MS, vm.state.value.scan.nextScanAt)

                vm.rescan()
                runCurrent()
                assertEquals(2, source.scans)

                // The wait starts over from the tapped scan, not the old one.
                advanceTimeBy(RESCAN_EVERY_MS - 1)
                assertEquals(2, source.scans)
                advanceTimeBy(2)
                assertEquals(3, source.scans)
            }
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun pausingClearsTheCountdown() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val vm = TellyscopeViewModel(NoTv(), clock = { 0L })
        try {
            with(TestScope(dispatcher)) {
                runCurrent()
                vm.pause()
                runCurrent()
                assertNull(vm.state.value.scan.nextScanAt)
            }
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
