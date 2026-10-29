// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.AppInfo
import app.tellyscope.data.Dock
import app.tellyscope.data.DockBook
import app.tellyscope.data.Docked
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DockTest {
    private fun app(pkg: String, enabled: Boolean = true, launchable: Boolean = true) =
        AppInfo(pkg, pkg.substringAfterLast('.'), null, false, enabled, null, "", launchable = launchable)

    private val youtube = Docked("com.google.android.youtube.tv", "YouTube")
    private val netflix = Docked("com.netflix.ninja", "Netflix")
    private val smarttube = Docked("org.smarttube.stable", "SmartTube")

    @Test fun move() {
        val dock = listOf(youtube, netflix, smarttube)
        assertEquals(listOf(netflix, smarttube, youtube), Dock.move(dock, 0, 2))
        assertEquals(listOf(smarttube, youtube, netflix), Dock.move(dock, 2, 0))
        assertEquals(dock, Dock.move(dock, 1, 3)) // off the end: nothing moves
    }

    @Test fun anUninstalledAppLeavesADisabledOneStays() {
        val dock = listOf(youtube, netflix, smarttube)
        val apps = listOf(app(youtube.packageName), app(netflix.packageName, enabled = false))
        assertEquals(listOf(youtube, netflix) to listOf(smarttube), Dock.prune(dock, apps))
    }

    @Test fun anEmptyAppListIsAFailedReadNotAnEmptyTv() {
        val dock = listOf(youtube, netflix)
        assertEquals(dock to emptyList<Docked>(), Dock.prune(dock, emptyList()))
    }

    @Test fun theBookKeepsOrderAcrossARestartAndFollowsARekey() {
        val file = File(Files.createTempDirectory("dock").toFile(), "docks.tsv")
        DockBook(file).apply {
            set("192.0.2.10:5555", listOf(netflix, youtube))
            set("other", listOf(Docked("com.x", "Tab\tin name")))
            rekey("192.0.2.10:5555", "serial-1")
        }
        val again = DockBook(file)
        assertEquals(listOf(netflix, youtube), again["serial-1"])
        assertEquals(emptyList<Docked>(), again["192.0.2.10:5555"])
        assertEquals(listOf(Docked("com.x", "Tab in name")), again["other"])
    }
}
