// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.ui.TellyscopeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun tv(id: String, state: DeviceState, loggedOutAt: Long? = null) =
    Device(id, ScanHit(id, 5555, ScanKind.Classic, id, null), state, null, null, null, 0L, loggedOutAt = loggedOutAt)

/** Remembered TVs as given; everything else comes from the empty fake. */
private class Remembered(private val tvs: List<Device>) : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    override suspend fun known() = tvs
}

/** The app opens on a logged-out TV; Back to TVs must leave it without a crash, whatever the other TV is doing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoggedOutBackTest {
    @get:Rule val compose = createComposeRule()

    private val out = tv("tv-a", DeviceState.NotAllowed, loggedOutAt = 1L)

    @Test fun noOtherTvOpensTheScan() = backFrom(listOf(out))
    @Test fun otherTvOnline() = backFrom(listOf(out, tv("tv-b", DeviceState.Online)))
    @Test fun otherTvOffline() = backFrom(listOf(out, tv("tv-b", DeviceState.Offline)))
    @Test fun otherTvStillConnecting() = backFrom(listOf(out, tv("tv-b", DeviceState.Waiting)))
    @Test fun otherTvFound() = backFrom(listOf(out, tv("tv-b", DeviceState.Found)))
    @Test fun otherTvLoggedOutToo() = backFrom(listOf(out, tv("tv-b", DeviceState.NotAllowed, loggedOutAt = 1L)))

    private fun backFrom(tvs: List<Device>) {
        val vm = TellyscopeViewModel(Remembered(tvs), clock = { 0L })
        compose.setContent { TellyscopeApp(vm) }
        compose.waitForIdle()
        // Two logged-out TVs hand the page back and forth, so press a few times.
        repeat(3) {
            compose.onNodeWithText("Back to TVs").performClick()
            compose.waitForIdle()
            if (compose.onAllNodesWithText("Back to TVs").fetchSemanticsNodes().isEmpty()) return
        }
    }
}
