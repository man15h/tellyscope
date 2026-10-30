// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.AppInfo
import app.tellyscope.data.FoldBook
import app.tellyscope.data.Proc
import app.tellyscope.data.UiText
import app.tellyscope.ui.AppsTab
import app.tellyscope.ui.splitApps
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppsTabsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun app(pkg: String, system: Boolean, locked: UiText? = null, home: String? = null) =
        AppInfo(pkg, pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }, null, system, true, locked, "1", homeInstead = home)

    private val HOME_SCREEN = UiText(R.string.data_protected_home_screen)
    private val launcher = app("com.tv.launcher", system = true, locked = HOME_SCREEN)
    private val apps = listOf(
        app("org.mine.jellyfin", system = false),
        app("org.mine.netflix", system = false),
        app("org.mine.projectivy", system = false, locked = HOME_SCREEN),
        app("com.maker.assistant", system = true),
        app("com.maker.ota", system = true),
        app("com.android.systemui", system = true, locked = UiText(R.string.data_protected_system)),
        app("com.google.android.gms", system = true, locked = UiText(R.string.data_protected_play_services)),
        // A launcher that's protected by name but offered as the new home screen stays changeable.
        app("org.other.home", system = false, locked = HOME_SCREEN, home = launcher.packageName),
        launcher,
    )
    private fun proc(pkg: String, ram: Int = 50) = Proc(1, pkg, pkg, 1.0, ram)
    private val procs = listOf(proc("org.mine.jellyfin"), proc("com.maker.assistant"), proc("com.android.systemui"), proc("com.maker.ota", ram = 0))

    private fun names(l: List<AppInfo>) = l.map { it.packageName.substringAfterLast('.') }

    @Test fun `running holds the apps using RAM, the user's first, the ones the TV needs under System`() {
        val split = splitApps(apps, procs, AppsTab.Running)
        assertEquals(listOf("jellyfin", "assistant"), names(split.apps))
        assertEquals(listOf("systemui"), names(split.system))
    }

    @Test fun `installed holds only the user's own apps, a locked home screen among them with no System part`() {
        val split = splitApps(apps, procs, AppsTab.Installed)
        assertEquals(listOf("home", "jellyfin", "netflix", "projectivy"), names(split.apps))
        assertEquals(emptyList<String>(), names(split.system))
    }

    @Test fun `running never puts a launcher the user installed under System, even a locked one`() {
        // No default home and the TV's own launcher off: a launcher the user installed stays protected.
        val projectivy = app("com.spocky.projengmenu", system = false, locked = HOME_SCREEN).copy(removable = true)
        val split = splitApps(listOf(projectivy, launcher), listOf(proc(projectivy.packageName), proc(launcher.packageName)), AppsTab.Running)
        assertEquals(listOf("projengmenu"), names(split.apps))
        assertEquals(listOf("launcher"), names(split.system))
    }

    @Test fun `built-in puts the maker's extras first, then what the TV needs`() {
        val split = splitApps(apps, procs, AppsTab.BuiltIn)
        assertEquals(listOf("assistant", "ota"), names(split.apps))
        assertEquals(listOf("gms", "launcher", "systemui"), names(split.system))
    }

    @Test fun `a protected launcher offered in place of an app the TV doesn't have stays locked`() {
        val gone = app("com.maker.home", system = true, locked = HOME_SCREEN, home = "com.not.installed")
        val split = splitApps(apps + gone, procs, AppsTab.BuiltIn)
        assertEquals(listOf("assistant", "ota"), names(split.apps))
        assertEquals(listOf("gms", "home", "launcher", "systemui"), names(split.system))
    }

    @Test fun `a fold is kept per tab across restarts, until it is opened again`() {
        val file = tmp.root.resolve("folds.properties")
        FoldBook(file).set(AppsTab.Running.name, true)
        FoldBook(file).set(AppsTab.BuiltIn.name, true)
        assertEquals(setOf("Running", "BuiltIn"), FoldBook(file).folded())
        FoldBook(file).set(AppsTab.Running.name, false)
        assertEquals(setOf("BuiltIn"), FoldBook(file).folded())
    }

    @Test fun `nothing is folded before the user folds it`() {
        assertEquals(emptySet<String>(), FoldBook(tmp.root.resolve("none.properties")).folded())
    }
}
