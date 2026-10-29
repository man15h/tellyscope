// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        compose.setContent { DebloatScreen(lists, {}, {}, {}) }
        compose.onNodeWithText("1 of 2").assertExists()
        // Part-ticked: ticks every app, the optional one too.
        compose.onNodeWithText("Extras").performClick()
        compose.onNodeWithText("2 of 2").assertExists()
        compose.onNodeWithText("Disable 2 apps").assertExists()
        // All ticked: unticks them all.
        compose.onNodeWithText("Extras").performClick()
        compose.onNodeWithText("0 of 2").assertExists()
    }

    @Test fun theTvsOwnLauncherIsNeverListed() {
        val stock = AppInfo("l.home", "Stock home", null, true, true, null, "1.0", builtInLauncher = true)
        val other = AppInfo("a.one", "One", null, true, true, null, "1.0")
        val list = DeviceList("test-tv", "Test TV", null, 30, emptySet(), listOf(ListGroup("Home", listOf(ListPackage("l.home", null, false), ListPackage("a.one", null, false)))))
        assertEquals(listOf("a.one"), suggest(list, listOf(stock, other), 30).flatMap { it.packages }.map { it.pkg })
        // With no list, the TV's own apps to pick from leave it out too.
        compose.setContent { DebloatScreen(Suggestions.NoList(null, emptyList()), {}, {}, {}, tvApps = listOf(stock, other)) }
        compose.onAllNodesWithText("Stock home").assertCountEquals(0)
        compose.onNodeWithText("One").assertExists()
    }
}
