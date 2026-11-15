// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceState
import app.tellyscope.data.ResetApp
import app.tellyscope.data.ResetPart
import app.tellyscope.data.ResetPlan
import app.tellyscope.data.ResetProgress
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.ui.Copy
import app.tellyscope.ui.DebloatRun
import app.tellyscope.ui.DebloatRunScreen
import app.tellyscope.ui.DeviceUi
import app.tellyscope.ui.ResetActions
import app.tellyscope.ui.ResetScreen
import app.tellyscope.ui.ResetUi
import app.tellyscope.ui.UiState
import app.tellyscope.ui.theme.TellyscopeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val APPS = listOf("YouTube Kids", "Sports", "Live Channels").map { ResetApp("com.acme.${it.lowercase().replace(' ', '.')}", it) }

/** Debloat and Reset hold Back while they run, so a run can't be cut off partway; Stop is the way out. */
@RunWith(RobolectricTestRunner::class)
// A phone-sized screen: the default one is too short for a result's last row to be laid out.
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RunLockTest {
    @get:Rule val compose = createComposeRule()

    private val taps = mutableListOf<String>()
    private lateinit var back: OnBackPressedDispatcher

    private fun show(content: @Composable () -> Unit) {
        // The run's progress indicators animate forever: drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            TellyscopeTheme(dark = false, dynamic = false) { content() }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun pressBack() {
        compose.runOnUiThread { back.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun debloat(run: DebloatRun) = show { DebloatRunScreen(run, back = { taps += "back" }, retry = { taps += "retry" }, stop = { taps += "stop" }) }

    private fun reset(ui: ResetUi) {
        val tv = Device("tv", ScanHit("tv", 5555, ScanKind.Classic, "tv", null), DeviceState.Online, null, null, null, 0L)
        val state = UiState(devices = listOf(tv), selectedId = tv.id, device = DeviceUi(reset = ui))
        show { ResetScreen(state, ResetActions(back = { taps += "back" }, retry = { taps += "retry" }, stop = { taps += "stop" })) }
    }

    @Test fun backDuringDebloatStaysAndSaysWhy() {
        debloat(DebloatRun(APPS, at = 1))
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        pressBack()
        compose.onNodeWithText(Copy.DEBLOAT_STILL_RUNNING.text()).assertExists()
        compose.onNodeWithText(Copy.DISABLING.text()).assertIsNotEnabled()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(listOf("stop"), taps)
    }

    @Test fun afterDebloatBackLeavesAgain() {
        debloat(DebloatRun(APPS, at = 3, ran = true))
        compose.onNodeWithContentDescription("Back").assertExists()
        pressBack()
        compose.onNodeWithText(Copy.DEBLOAT_STILL_RUNNING.text()).assertDoesNotExist()
        assertEquals(listOf("back"), taps)
    }

    /** Stop tapped: the line names the app it ends on, and Stop is gone. */
    @Test fun stoppingDebloatNamesTheAppItEndsOn() {
        debloat(DebloatRun(APPS, at = 1, stopping = true))
        compose.onNodeWithText(Copy.stoppingAfter("Sports").text()).assertExists()
        compose.onNodeWithText("Stop").assertDoesNotExist()
    }

    @Test fun stoppedDebloatSaysWhatItDidntChange() {
        debloat(DebloatRun(APPS, at = 2, ran = true, stopping = true))
        compose.onNodeWithText(Copy.DEBLOAT_STOPPED.text()).assertExists()
        compose.onNodeWithText(Copy.turnedOff(2, 3).text()).assertExists()
        compose.onNodeWithText(Copy.notChanged(1).text()).assertExists()
        compose.onNodeWithText("Live Channels").assertExists()
        compose.onNodeWithText(Copy.disableTheRest(1).text()).performClick()
        assertEquals(listOf("retry"), taps)
    }

    private val plan = ResetPlan(turnOn = APPS)

    @Test fun backDuringResetStaysAndSaysWhy() {
        reset(ResetUi(plan = plan, running = plan, progress = ResetProgress(ResetPart.TurnOn, 1, 3)))
        compose.onNodeWithContentDescription("Back to the TV's page").assertDoesNotExist()
        pressBack()
        compose.onNodeWithText(Copy.RESET_STILL_RUNNING.text()).assertExists()
        compose.onNodeWithText(Copy.RESETTING.text()).assertIsNotEnabled()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(listOf("stop"), taps)
    }

    @Test fun afterResetBackLeavesAgain() {
        reset(ResetUi(plan = plan, ran = true))
        compose.onNodeWithContentDescription("Back to the TV's page").assertExists()
        pressBack()
        assertEquals(listOf("back"), taps)
    }

    @Test fun stoppedResetSaysWhatsNotDone() {
        reset(ResetUi(plan = plan, ran = true, left = ResetPlan(turnOn = APPS.drop(2))))
        compose.onNodeWithText(Copy.RESET_STOPPED.text()).assertExists()
        compose.onNodeWithText(Copy.turnedOn(2, 3).text()).assertExists()
        compose.onNodeWithText(Copy.resetLeft(ResetPlan(turnOn = APPS.drop(2))).text()).assertExists()
        compose.onNodeWithText(Copy.FINISH_RESET.text()).performClick()
        assertEquals(listOf("retry"), taps)
    }
}
