// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.DeviceList
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.ListGroup
import app.tellyscope.data.ListPackage
import app.tellyscope.data.Suggestions
import app.tellyscope.data.UiText
import app.tellyscope.data.suggest
import app.tellyscope.ui.DeviceUi
import app.tellyscope.ui.debloatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebloatStateTest {
    private val list = DeviceList(
        "x", "X", null, 30, keep = setOf("com.acme.keep"),
        groups = listOf(ListGroup("Junk", listOf("com.acme.a", "com.acme.b", "com.acme.c", "com.acme.keep").map { ListPackage(it, null, false) })),
    )

    private fun app(pkg: String, enabled: Boolean, protected: UiText? = null) = AppInfo(pkg, pkg, null, true, enabled, protected, "1")

    private fun turnedOff(pkg: String) = HistoryEntry(1, "tv", 0, Action.Disable, pkg, pkg, Action.Enable, null)

    private fun ui(apps: List<AppInfo>, history: List<HistoryEntry> = emptyList()) =
        DeviceUi(apps = apps, history = history, lists = Suggestions.Ready(null, list, 30, suggest(list, apps, 30), emptyList()))

    @Test fun `an app on the list that came back on counts once`() {
        // a came back after Tellyscope turned it off; b is still on; c is off.
        val d = debloatState(ui(listOf(app("com.acme.a", true), app("com.acme.b", true), app("com.acme.c", false)), listOf(turnedOff("com.acme.a"))))
        assertEquals(3, d.total)
        assertEquals(2, d.on)
        assertEquals(1, d.back)
        assertEquals(2, d.count)
        assertTrue(d.todo)
    }

    @Test fun `an app off the list that came back on adds to the count`() {
        val d = debloatState(ui(listOf(app("com.acme.a", true), app("com.acme.b", false), app("com.other", true)), listOf(turnedOff("com.other"))))
        assertEquals(1, d.on)
        assertEquals(1, d.back)
        assertEquals(2, d.count)
    }

    @Test fun `nothing left means nothing to do`() {
        val d = debloatState(ui(listOf(app("com.acme.a", false), app("com.acme.b", false))))
        assertEquals(2, d.total)
        assertEquals(0, d.on)
        assertEquals(0, d.count)
        assertFalse(d.todo)
    }

    @Test fun `keep entries and protected apps are not suggestions`() {
        val d = debloatState(ui(listOf(app("com.acme.keep", true), app("com.acme.a", true, protected = UiText(R.string.data_protected_system)))))
        assertEquals(0, d.total)
        assertFalse(d.todo)
    }

    @Test fun `an unknown TV still has something to do`() {
        assertTrue(debloatState(DeviceUi(lists = Suggestions.NoList(null, emptyList()))).todo)
    }
}
