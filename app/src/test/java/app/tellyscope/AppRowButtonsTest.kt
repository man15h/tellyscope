package app.tellyscope

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Proc
import app.tellyscope.data.UiText
import app.tellyscope.ui.AppRow
import app.tellyscope.ui.Copy
import app.tellyscope.ui.DeviceActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An app row's buttons: Open and Stop by whether it runs and where, then Uninstall or Disable by who installed it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppRowButtonsTest {
    @get:Rule val compose = createComposeRule()

    private val pkg = "org.jellyfin.androidtv"
    private val userApp = AppInfo(pkg, "Jellyfin", null, false, true, null, "0.17.4", removable = true, launchable = true)
    private val tvApp = AppInfo(pkg, "Jellyfin", null, true, true, null, "0.17.4", launchable = true)
    private val running = listOf(Proc(2201, pkg, pkg, 1.0, 180))
    private val other = "com.spocky.projengmenu"

    /** Opens [app]'s row and returns the buttons it shows, in the order checked. */
    private fun buttons(app: AppInfo, procs: List<Proc> = emptyList(), onScreen: String? = other): List<Action> {
        compose.setContent { AppRow(app, procs, null, busy = false, actions = DeviceActions(), onScreen = onScreen) }
        compose.onNodeWithText("Jellyfin").performClick()
        return listOf(Action.Enable, Action.Open, Action.Stop, Action.Disable, Action.Uninstall)
            .filter { a -> runCatching { compose.onNodeWithText(Copy.button(a).text()).assertExists() }.isSuccess }
    }

    @Test fun notRunning() {
        assertEquals(listOf(Action.Open, Action.Uninstall), buttons(userApp))
        compose.onNodeWithText("Not running").assertExists()
    }

    @Test fun runningInTheBackground() {
        assertEquals(listOf(Action.Open, Action.Stop, Action.Uninstall), buttons(userApp, running))
        compose.onNodeWithText("Running in the background").assertExists()
    }

    @Test fun onScreen() {
        assertEquals(listOf(Action.Stop, Action.Uninstall), buttons(userApp, running, onScreen = pkg))
        compose.onNodeWithText("On screen").assertExists()
    }

    @Test fun withoutTheScreenReadARunningAppIsJustRunning() {
        assertEquals(listOf(Action.Open, Action.Stop, Action.Uninstall), buttons(userApp, running, onScreen = null))
        compose.onNodeWithText("Running").assertExists()
    }

    @Test fun aUserAppThatsOffTurnsOnOrUninstalls() {
        assertEquals(listOf(Action.Enable, Action.Uninstall), buttons(userApp.copy(enabled = false)))
    }

    @Test fun anAppTheTvCameWithDisablesAndNeverUninstalls() {
        assertEquals(listOf(Action.Open, Action.Disable), buttons(tvApp))
    }

    @Test fun anAppTheTvCameWithThatsOffOnlyTurnsOn() {
        assertEquals(listOf(Action.Enable), buttons(tvApp.copy(enabled = false)))
    }

    @Test fun aServiceWithNoIconStopsButDoesntOpen() {
        assertEquals(listOf(Action.Stop, Action.Disable), buttons(tvApp.copy(launchable = false), running))
    }

    private val protectedApp = tvApp.copy(protectedReason = UiText(R.string.data_protected_settings))

    @Test fun aProtectedAppStillOpensButNeverStopsOrRemoves() {
        assertEquals(listOf(Action.Open), buttons(protectedApp, running))
    }

    @Test fun aProtectedAppWithNoIconShowsNoButtons() {
        assertEquals(emptyList<Action>(), buttons(protectedApp.copy(launchable = false), running))
    }

    @Test fun openRunsAtOnce() {
        val opened = mutableListOf<String>()
        compose.setContent { AppRow(userApp, emptyList(), null, busy = false, actions = DeviceActions(open = { opened += it }), onScreen = other) }
        compose.onNodeWithText("Jellyfin").performClick()
        compose.onNodeWithText(Copy.button(Action.Open).text()).performClick()
        assertEquals(listOf(pkg), opened)
    }
}
