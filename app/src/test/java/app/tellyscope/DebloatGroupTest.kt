// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import app.tellyscope.data.AppInfo
import app.tellyscope.data.DeviceList
import app.tellyscope.data.ListGroup
import app.tellyscope.data.ListPackage
import app.tellyscope.data.Suggested
import app.tellyscope.data.SuggestedGroup
import app.tellyscope.data.Suggestions
import app.tellyscope.data.suggest
import app.tellyscope.ui.DebloatScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebloatGroupTest {
    @get:Rule val compose = createComposeRule()

    private fun app(pkg: String, ticked: Boolean, optional: Boolean = false) = Suggested(pkg, pkg, null, optional, ticked)

    private val lists = Suggestions.Ready(
        null,
        DeviceList("test-tv", "Test TV", null, null, emptySet(), emptyList()),
        null,
        listOf(
            SuggestedGroup("Extras", listOf(app("a.one", ticked = true), app("a.two", ticked = false, optional = true))),
        ),
        emptyList(),
    )

    @Test fun aHeadingTicksItsWholeGroupThenUnticksIt() {
        compose.setContent { DebloatScreen(lists, {}, { _, _ -> }, {}) }
        compose.onNodeWithText("1 of 2").assertExists()
        // Part-ticked: ticks every app, the optional one too.
        compose.onNodeWithText("Extras").performClick()
        compose.onNodeWithText("2 of 2").assertExists()
        compose.onNodeWithText("Disable 2 apps").assertExists()
        // All ticked: unticks them all.
        compose.onNodeWithText("Extras").performClick()
        compose.onNodeWithText("0 of 2").assertExists()
    }

    @Test fun aRefreshKeepsTheTicksSetByHand() {
        var shown by mutableStateOf<Suggestions>(lists)
        compose.setContent { DebloatScreen(shown, {}, { _, _ -> }, {}) }
        compose.onNodeWithText("a.one").performClick()
        compose.onNodeWithText("0 of 2").assertExists()
        // The same list again with an app added: a.one stays unticked, the new one takes the list's tick.
        shown = lists.copy(groups = listOf(SuggestedGroup("Extras", lists.groups.single().packages + app("a.three", ticked = true))))
        compose.onNodeWithText("1 of 3").assertExists()
        compose.onNodeWithText("Disable 1 app").assertExists()
    }

    @Test fun theUntickedAppsAreHiddenOnlyWhenTheBoxIsTicked() {
        var got: Pair<List<String>, List<String>>? = null
        compose.setContent { DebloatScreen(lists, {}, { apps, hide -> got = apps.map { it.packageName } to hide }, {}) }
        compose.onNodeWithText("Disable 1 app").performClick()
        compose.onNodeWithText("Don't suggest a.two again").performClick()
        compose.onNodeWithText("Yes, disable them").performClick()
        assertEquals(listOf("a.one") to listOf("a.two"), got)
    }

    @Test fun theBoxStartsOffAndShowsOnlyWithSomethingUnticked() {
        var got: List<String>? = null
        compose.setContent { DebloatScreen(lists, {}, { _, hide -> got = hide }, {}) }
        compose.onNodeWithText("Extras").performClick()
        compose.onNodeWithText("Disable 2 apps").performClick()
        compose.onAllNodesWithText("Don't suggest", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Yes, disable them").performClick()
        assertEquals(emptyList<String>(), got)
    }

    @Test fun aHiddenAppComesBackWithSuggestAgain() {
        val again = mutableListOf<String>()
        compose.setContent { DebloatScreen(lists.copy(hidden = listOf(app("a.three", ticked = false))), {}, { _, _ -> }, {}, onSuggestAgain = { again += it }) }
        // The row sits at the foot of the list, below the fold.
        compose.onNode(hasScrollAction()).performScrollToKey("hidden")
        compose.onNodeWithText("Not suggested (1)").performClick()
        compose.onNode(hasScrollAction()).performScrollToKey("h:a.three")
        compose.onNodeWithText("Suggest again").performClick()
        assertEquals(listOf("a.three"), again)
    }

    @Test fun theTvsOwnLauncherIsNeverListed() {
        val stock = AppInfo("l.home", "Stock home", null, true, true, null, "1.0", builtInLauncher = true)
        val other = AppInfo("a.one", "One", null, true, true, null, "1.0")
        val list = DeviceList("test-tv", "Test TV", null, 30, emptySet(), listOf(ListGroup("Home", listOf(ListPackage("l.home", null, false), ListPackage("a.one", null, false)))))
        assertEquals(listOf("a.one"), suggest(list, listOf(stock, other), 30).flatMap { it.packages }.map { it.pkg })
        // With no list, the TV's own apps to pick from leave it out too.
        compose.setContent { DebloatScreen(Suggestions.NoList(null, emptyList()), {}, { _, _ -> }, {}, tvApps = listOf(stock, other)) }
        compose.onAllNodesWithText("Stock home").assertCountEquals(0)
        compose.onNodeWithText("One").assertExists()
    }
}
