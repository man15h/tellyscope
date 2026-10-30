// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.Action
import app.tellyscope.data.HistoryBook
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.UiText
import app.tellyscope.data.UiTextException
import app.tellyscope.data.uninstallAfterHomeBack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uninstalling the launcher on Home: Home goes back first, and nothing is removed if that fails. */
class UninstallHomeTest {
    private val steps = mutableListOf<String>()
    private val removed = HistoryEntry(1, "tv", 0L, Action.Uninstall, "com.spocky.projengmenu", "Projectivy Launcher", null, null)

    private val switched = HistoryEntry(0, "tv", 0L, Action.HomeBack, "com.spocky.projengmenu", "Google TV Home", Action.Home, null)
    private val dropped = mutableListOf<Long>()

    private suspend fun uninstall(): HistoryEntry {
        steps += "uninstall"
        return removed
    }

    @Test fun homeGoesBackBeforeTheUninstall() = runTest {
        assertEquals(removed, uninstallAfterHomeBack({ steps += "home back"; switched }, ::uninstall) { dropped += it })
        assertEquals(listOf("home back", "uninstall"), steps)
        assertEquals(listOf(0L), dropped)
    }

    @Test fun theSwitchBacksUndoGoesWithTheLauncher() = runTest {
        val book = HistoryBook()
        uninstallAfterHomeBack(
            { book.record("tv", 0L, Action.HomeBack, "com.spocky.projengmenu", "Google TV Home", undo = Action.Home) },
            { book.record("tv", 1L, Action.Uninstall, "com.spocky.projengmenu", "Projectivy Launcher", undo = null) },
            book::dropUndo,
        )
        val back = book.entries("tv").single { it.action == Action.HomeBack }
        assertNull(back.undo)
    }

    @Test fun aSwitchThatFailsUninstallsNothingAndSaysSo() = runTest {
        val e = runCatching { uninstallAfterHomeBack({ throw IllegalStateException("the TV kept the home screen it has") }, ::uninstall) { dropped += it } }.exceptionOrNull()
        assertEquals("Home didn't switch back: the TV kept the home screen it has. Nothing was uninstalled", (e as UiTextException).text.text())
        assertTrue(steps.isEmpty() && dropped.isEmpty())
    }

    @Test fun aServiceStillOnUninstallsNothing() = runTest {
        val why = "switched back, but Projectivy Launcher's accessibility service is still on. Turn it off in the TV's Settings → Accessibility"
        val e = runCatching { uninstallAfterHomeBack({ throw UiTextException(UiText(R.string.data_service_not_off, "Projectivy Launcher")) }, ::uninstall) { dropped += it } }.exceptionOrNull()
        assertEquals("$why. Nothing was uninstalled", (e as UiTextException).text.text())
        assertTrue(steps.isEmpty() && dropped.isEmpty())
    }
}
