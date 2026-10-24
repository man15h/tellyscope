// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.DeviceList
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.Suggested
import app.tellyscope.data.SuggestedGroup
import app.tellyscope.data.Suggestions
import app.tellyscope.ui.AppRow
import app.tellyscope.ui.Copy
import app.tellyscope.ui.DebloatScreen
import app.tellyscope.ui.DeviceActions
import app.tellyscope.ui.DisableDialog
import app.tellyscope.ui.HistoryList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BusyGatingTest {
    @get:Rule val compose = createComposeRule()

    private val pkg = "com.example"
    private val app = AppInfo(pkg, "Example", null, false, true, null, "1.0", removable = true)

    @Test fun appButtonsWaitWhileTheTvIsBusy() {
        compose.setContent { AppRow(app, emptyList(), null, busy = false, actions = DeviceActions(), locked = true) }
        compose.onNodeWithText("Example").performClick()
        compose.onNodeWithText(Copy.button(Action.Disable)).assertIsNotEnabled()
        compose.onNodeWithText(Copy.button(Action.Uninstall)).assertIsNotEnabled()
    }

    @Test fun confirmWaitsWhileTheTvIsBusy() {
        // Proposed before a restore started: the explanation stays, its button waits.
        compose.setContent { AppRow(app, emptyList(), pkg to Action.Disable, busy = false, actions = DeviceActions(), locked = true) }
        compose.onNodeWithText(Copy.confirm(Action.Disable)).assertIsNotEnabled()
    }

    @Test fun undoAndRestoreWaitWhileTheTvIsBusy() {
        val history = listOf(HistoryEntry(1, "tv", 0L, Action.Disable, pkg, "Example", Action.Enable, null))
        compose.setContent { HistoryList(history, 0L, onUndo = {}, busy = true) }
        compose.onNodeWithText(Copy.undo(Action.Enable)).assertIsNotEnabled()
        compose.onNodeWithText("Restore everything (1)").assertIsNotEnabled()
    }

    @Test fun restoreKeepsItsOwnLabel() {
        val history = listOf(HistoryEntry(1, "tv", 0L, Action.Disable, pkg, "Example", Action.Enable, null))
        compose.setContent { HistoryList(history, 0L, onUndo = {}, restoring = true) }
        compose.onNodeWithText("Turning apps back on…").assertIsNotEnabled()
        compose.onNodeWithText(Copy.undo(Action.Enable)).assertIsNotEnabled()
    }

    @Test fun idleUndoStillWorks() {
        val history = listOf(HistoryEntry(1, "tv", 0L, Action.Disable, pkg, "Example", Action.Enable, null))
        compose.setContent { HistoryList(history, 0L, onUndo = {}) }
        compose.onNodeWithText(Copy.undo(Action.Enable)).assertIsEnabled()
    }

    private val lists = Suggestions.Ready(
        null,
        DeviceList("test-tv", "Test TV", null, null, emptySet(), emptyList()),
        null,
        listOf(SuggestedGroup("Extras", listOf(Suggested(pkg, "Example", null, optional = false, ticked = true)))),
        emptyList(),
    )

    @Test fun debloatWaitsWhileTheTvIsBusy() {
        // A Disable started on the Apps tab is still running when Debloat opens.
        compose.setContent { DebloatScreen(lists, {}, {}, {}, busy = true) }
        compose.onNodeWithText("Disable 1 app").assertIsNotEnabled()
    }

    @Test fun debloatConfirmWaitsWhileTheTvIsBusy() {
        val apps = listOf(Suggested(pkg, "Example", null, optional = false, ticked = true))
        compose.setContent { DisableDialog(apps, onDisable = {}, onDismiss = {}, enabled = false) }
        compose.onNodeWithText("Yes, disable them").assertIsNotEnabled()
    }

    @Test fun idleDebloatStillWorks() {
        compose.setContent { DebloatScreen(lists, {}, {}, {}) }
        compose.onNodeWithText("Disable 1 app").assertIsEnabled()
    }

    @Test fun launcherSwitchWithItsTargetGoneIsCancelled() {
        // "Set as default launcher" was asked, then the launcher was turned off in Debloat.
        var cancels = 0
        val off = app.copy(enabled = false, homeInstead = "com.stock.home")
        compose.setContent { AppRow(off, emptyList(), pkg to Action.Home, busy = false, actions = DeviceActions(cancel = { cancels++ }), homeLabel = "Stock") }
        compose.waitForIdle()
        assertEquals(1, cancels)
        compose.onNodeWithText(Copy.confirm(Action.Home)).assertDoesNotExist()
    }

    @Test fun switchBackWithItsTargetGoneIsCancelled() {
        var cancels = 0
        val off = app.copy(enabled = false, homeBack = "Stock")
        compose.setContent { AppRow(off, emptyList(), pkg to Action.HomeBack, busy = false, actions = DeviceActions(cancel = { cancels++ })) }
        compose.waitForIdle()
        assertEquals(1, cancels)
    }
}
