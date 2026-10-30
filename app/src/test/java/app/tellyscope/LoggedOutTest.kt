// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceState
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.ui.Copy
import app.tellyscope.ui.LoggedOutScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoggedOutTest {
    @get:Rule val compose = createComposeRule()

    private fun tv(id: String, nickname: String, loggedOutAt: Long? = null) = Device(
        id, ScanHit(id, 5555, ScanKind.Classic, id, null), if (loggedOutAt != null) DeviceState.NotAllowed else DeviceState.Online,
        null, "XGIMI", null, 0L, nickname = nickname, loggedOutAt = loggedOutAt,
    )

    private val devices = mutableStateOf(listOf(tv("projector", "Projector"), tv("kitchen", "Kitchen")))
    private var viewed: String? = null

    /** The paused clock doesn't flush state writes, so tell the watch about this one before stepping. */
    private fun logOut(id: String) {
        devices.value = devices.value.map { if (it.id == id) tv(it.id, it.nickname!!, loggedOutAt = 1L) else it }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun watch(selected: String) {
        // The snackbar times out on the test clock, so step it by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val host = remember { SnackbarHostState() }
            LoggedOutSnackbars(host, { devices.value }, { selected }, onView = { viewed = it })
            SnackbarHost(host)
        }
        compose.mainClock.advanceTimeBy(100)
    }

    @Test fun anotherTvLoggingOutSaysSoAndViewOpensIt() {
        watch(selected = "kitchen")
        logOut("projector")
        compose.onNodeWithText(Copy.loggedOutOf("Projector").text()).assertExists()
        compose.onNodeWithText("View").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("projector", viewed)
    }

    @Test fun theTvOnScreenGetsThePageNotASnackbar() {
        watch(selected = "projector")
        logOut("projector")
        compose.onNodeWithText(Copy.loggedOutOf("Projector").text()).assertDoesNotExist()
    }

    @Test fun alreadyLoggedOutAtLaunchGetsNoSnackbar() {
        devices.value = listOf(tv("projector", "Projector", loggedOutAt = 1L), tv("kitchen", "Kitchen"))
        watch(selected = "kitchen")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(Copy.loggedOutOf("Projector").text()).assertDoesNotExist()
    }

    @Test fun connectAgainLeavesThePage() {
        var connected = false
        compose.setContent { LoggedOutScreen(tv("projector", "Projector", 1L), loggedOutAt = 1L, now = 2L, onConnect = { connected = true }, onBack = {}) }
        compose.onNodeWithText("Projector · XGIMI").assertExists()
        compose.onNodeWithText("Logged out just now").assertExists()
        compose.onNodeWithText("Connect again").performClick()
        assertTrue(connected)
    }

    @Test fun timeLineSaysWhen() {
        val hour = 3_600_000L
        assertEquals("Logged out just now", Copy.loggedOutAt(0L, 60_000L).text())
        assertEquals("Logged out yesterday", Copy.loggedOutAt(0L, 30 * hour).text())
        assertEquals("Logged out 3 days ago", Copy.loggedOutAt(0L, 80 * hour).text())
        // A day is a calendar day: 23:00 is "yesterday" at 09:00 the next morning, ten hours on.
        val lateEvening = LocalDate.of(2026, 10, 25).atTime(23, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("Logged out yesterday", Copy.loggedOutAt(lateEvening, lateEvening + 10 * hour).text())
    }
}
