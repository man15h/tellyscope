// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tellyscope.data.Action
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.UiText
import app.tellyscope.ui.Copy
import app.tellyscope.ui.Done
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SnackbarsTest {
    @get:Rule val compose = createComposeRule()

    private val done = MutableStateFlow<Done?>(null)
    private val notice = MutableStateFlow<UiText?>(null)
    private var undone: Done? = null

    private fun show() {
        // The snackbar times out on the test clock, so step it by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val host = remember { SnackbarHostState() }
            Snackbars(host, done, notice, { done.value = null }, { notice.value = null }) { undone = it }
            SnackbarHost(host)
        }
        compose.mainClock.advanceTimeBy(100)
    }

    @Test fun noticeStaysOnScreen() {
        show()
        notice.value = UiText.plural(R.plurals.vm_turned_back_on, 2)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Turned 2 apps back on.").assertExists()
        // Shown once: it was taken off the queue as it went up.
        assertEquals(null, notice.value)
    }

    @Test fun undoReachesTheTvTheActionRanOn() {
        show()
        val entry = HistoryEntry(7, "tv-a", 0L, Action.Disable, "com.example", "Example", Action.Enable, null)
        done.value = Done("tv-a", entry)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(Copy.done(Action.Disable, "Example").text()).assertExists()
        compose.onNodeWithText(Copy.undo(Action.Enable).text()).performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(Done("tv-a", entry), undone)
    }
}
