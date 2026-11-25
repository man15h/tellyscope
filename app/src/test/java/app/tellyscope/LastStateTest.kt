// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.AppInfo
import app.tellyscope.data.DeviceList
import app.tellyscope.data.LastStateBook
import app.tellyscope.data.ListEntry
import app.tellyscope.data.ListGroup
import app.tellyscope.data.ListPackage
import app.tellyscope.data.Suggested
import app.tellyscope.data.SuggestedGroup
import app.tellyscope.data.Suggestions
import app.tellyscope.data.UiText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each TV's apps and lists, saved so its page opens on them at the next launch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LastStateTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tv = "sn:ABC/123"
    private fun book() = LastStateBook(tmp.root) { (it as UiText.Raw).text }

    private val icon = byteArrayOf(1, 2, 3)
    private val jellyfin = AppInfo(
        "org.jellyfin.androidtv", "Jellyfin", 0xFF112233, false, true, null, "0.17.4",
        icon = icon, removable = true, launchable = true, warn = "Casting stops",
    )
    private val launcher = AppInfo(
        "com.google.android.tvlauncher", "Google TV Home", null, true, true, UiText.Raw("Runs the home screen"), "1.0",
        home = true, builtInLauncher = true, answersHome = true, tvHome = false,
    )
    private val entry = ListEntry("tcl-c6", "TCL C6", mapOf("model" to listOf("C6", "C6K")), 30, "devices/tcl-c6.json", "ab12")
    private val ready = Suggestions.Ready(
        commit = "c0ffee",
        list = DeviceList("tcl-c6", "TCL C6", "Android 11", 30, setOf("com.tcl.keep"), listOf(ListGroup("Ads", listOf(ListPackage("com.tcl.ads", "Banner ads", false))))),
        sdk = 30,
        groups = listOf(SuggestedGroup("Ads", listOf(Suggested("com.tcl.ads", "TCL Ads", "Banner ads", false, true)))),
        devices = listOf(entry),
        byMaker = true,
        hidden = listOf(Suggested("com.tcl.store", "TCL Store", null, true, false, warn = "Updates stop")),
    )

    @Test fun appsAndListsSurviveARestart() {
        book().save(tv, listOf(jellyfin, launcher), ready)
        val last = book().load(tv)!!
        assertEquals(listOf(jellyfin.copy(icon = null), launcher), last.apps.map { it.copy(icon = null) })
        assertArrayEquals(icon, last.apps[0].icon)
        assertEquals(ready, last.lists)
    }

    @Test fun anUnavailableListKeepsTheOneBefore() {
        book().save(tv, listOf(jellyfin), ready)
        book().save(tv, listOf(launcher), Suggestions.Unavailable())
        val last = book().load(tv)!!
        assertEquals(listOf("com.google.android.tvlauncher"), last.apps.map { it.packageName })
        assertEquals(ready, last.lists)
    }

    @Test fun noListIsSavedAsItself() {
        val none = Suggestions.NoList(null, listOf(entry.copy(match = null)))
        book().save(tv, listOf(jellyfin), none)
        assertEquals(none, book().load(tv)!!.lists)
    }

    @Test fun forgetAndANewSourceDropWhatWasSaved() {
        book().save(tv, listOf(jellyfin), ready)
        book().dropLists()
        assertNull(book().load(tv)!!.lists)
        book().forget(tv)
        assertNull(book().load(tv))
    }

    @Test fun aDamagedFileLoadsAsNothing() {
        book().save(tv, listOf(jellyfin), ready)
        tmp.root.listFiles()!!.single().writeText("{\"v\":1,\"apps\":[{\"pkg\":")
        assertNull(book().load(tv))
    }
}
