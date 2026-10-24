// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ScanEvent
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.ui.RowState
import app.tellyscope.ui.ScanScreen
import app.tellyscope.ui.ScanUi
import app.tellyscope.ui.TellyscopeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Off Wi-Fi on the first scan, on it from the second; [lan] stands in for the network callback. */
private class JoinsWifi : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    var scans = 0
    val lan = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override suspend fun known() = emptyList<Device>()

    override fun scan() = flow {
        if (++scans == 1) emit(ScanEvent.NoLan)
        emit(ScanEvent.Done)
    }

    override fun lanUp() = lan
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OffWifiTest {
    @get:Rule val compose = createComposeRule()

    private val tv = ScanHit("192.0.2.7", 37211, ScanKind.Wireless, "chromecast", "Google")

    private fun show(scan: ScanUi, onOpenWifi: () -> Unit = {}) = compose.setContent {
        ScanScreen(scan, 0L, {}, {}, { _, _ -> }, {}, { null }, onOpenWifi = onOpenWifi)
    }

    @Test fun noLanShowsTheWifiPrompt() {
        var opened = false
        show(ScanUi(noLan = true)) { opened = true }
        compose.onNodeWithText("This phone isn't on Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("Add by IP address").assertIsDisplayed()
        compose.onNodeWithText("Open Wi-Fi settings").performClick()
        assertTrue(opened)
    }

    @Test fun aTvThatAnnouncedItselfStillShows() {
        show(ScanUi(noLan = true, hits = listOf(tv), rows = mapOf(tv.ip to RowState.Found)))
        compose.onNodeWithText("chromecast").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This phone isn't on Wi-Fi").assertDoesNotExist()
    }

    @Test fun onWifiShowsTheScan() {
        show(ScanUi())
        compose.onNodeWithText("Add your TV").assertIsDisplayed()
        compose.onNodeWithText("This phone isn't on Wi-Fi").assertDoesNotExist()
    }

    @Test fun joiningWifiScansAgain() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val source = JoinsWifi()
        val vm = TellyscopeViewModel(source, clock = { 0L })
        try {
            with(TestScope(dispatcher)) {
                runCurrent()
                assertTrue(vm.state.value.scan.noLan)

                source.lan.tryEmit(Unit)
                runCurrent()
                assertEquals(2, source.scans)
                assertFalse(vm.state.value.scan.noLan)

                // Already on the LAN: a network change doesn't restart the scan.
                source.lan.tryEmit(Unit)
                runCurrent()
                assertEquals(2, source.scans)
            }
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun anEmptyScanTriesAgainEvery15Seconds() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val source = JoinsWifi()
        val vm = TellyscopeViewModel(source, clock = { 0L })
        try {
            with(TestScope(dispatcher)) {
                runCurrent()
                assertEquals(1, source.scans)
                advanceTimeBy(15_001)
                assertEquals(2, source.scans)
                advanceTimeBy(15_001)
                assertEquals(3, source.scans)
            }
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
