// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import app.tellyscope.adb.ApkInfo
import app.tellyscope.adb.AppFacts
import app.tellyscope.adb.ConnectionRequest
import app.tellyscope.adb.InstallRefusal
import app.tellyscope.adb.InstalledVersion
import app.tellyscope.adb.Progress
import app.tellyscope.adb.Reports
import app.tellyscope.adb.TvFile
import app.tellyscope.adb.TvFolder
import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Docked
import app.tellyscope.data.AppNames
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.TvSettings
import app.tellyscope.data.TvAccess
import app.tellyscope.data.TvTweaks
import app.tellyscope.data.ListEntry
import app.tellyscope.data.PickedApp
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.ScanProgress
import app.tellyscope.data.Suggested
import app.tellyscope.data.SuggestedGroup
import app.tellyscope.data.Proc
import app.tellyscope.data.Suggestions
import app.tellyscope.ui.AboutDialog
import app.tellyscope.ui.Removal
import app.tellyscope.ui.ConnectedDevicesCard
import app.tellyscope.ui.AccessWarning
import app.tellyscope.ui.AccessScreen
import app.tellyscope.ui.AccessActions
import app.tellyscope.ui.AdvancedScreen
import app.tellyscope.ui.AppsTab
import app.tellyscope.ui.ConnectScreen
import app.tellyscope.ui.DebloatGate
import app.tellyscope.ui.DebloatScreen
import app.tellyscope.ui.DeviceActions
import app.tellyscope.ui.DeviceTabsScreen
import app.tellyscope.ui.RESTORING
import app.tellyscope.ui.TvTab
import app.tellyscope.data.LastKnown
import app.tellyscope.ui.Copy
import app.tellyscope.ui.HistoryScreen
import app.tellyscope.data.ResetApp
import app.tellyscope.data.ResetFailure
import app.tellyscope.data.ResetHome
import app.tellyscope.data.ResetPart
import app.tellyscope.data.ResetPlan
import app.tellyscope.data.ResetProgress
import app.tellyscope.ui.ResetActions
import app.tellyscope.ui.ResetScreen
import app.tellyscope.ui.ResetUi
import app.tellyscope.ui.TweaksBlock
import app.tellyscope.ui.DeviceUi
import app.tellyscope.ui.DisableDialog
import app.tellyscope.ui.ForgetDialog
import app.tellyscope.ui.FilesBlock
import app.tellyscope.ui.FilesUi
import app.tellyscope.ui.RestoreDialog
import app.tellyscope.ui.PermissionScreen
import app.tellyscope.ui.WelcomeScreen
import app.tellyscope.ui.RebootDialog
import app.tellyscope.ui.RemoteActions
import app.tellyscope.ui.RemoteScreen
import app.tellyscope.ui.Restart
import app.tellyscope.ui.RowState
import app.tellyscope.ui.ScanScreen
import app.tellyscope.ui.ScanUi
import app.tellyscope.ui.SideloadUi
import app.tellyscope.ui.TransferUi
import app.tellyscope.ui.brands
import app.tellyscope.ui.name
import app.tellyscope.ui.AddRestScreen
import app.tellyscope.ui.ReportKind
import app.tellyscope.ui.ReportScreen
import app.tellyscope.ui.ReportUi
import app.tellyscope.ui.SettingsScreen
import app.tellyscope.ui.SHARE_TEXT
import app.tellyscope.data.RELEASES
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tellyscope.ui.UiState
import app.tellyscope.ui.theme.TellyscopeTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each screen on fixed fake data. `./gradlew :app:recordRoborazziDebug`
 * writes PNGs to app/build/outputs/roborazzi; `verifyRoborazziDebug` diffs them.
 * Dynamic color is off so the branded fallback scheme is what gets reviewed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val now = 1_760_000_000_000L
    private val fake = FakeDeviceSource(clock = { now }, random = Random(7))

    private val xgimi = ScanHit("192.168.1.23", 5555, ScanKind.Classic, "xgimi-projector", "XGIMI")
    private val box = ScanHit("192.168.1.41", 5555, ScanKind.Classic, "android-7f3a", "Amlogic")
    private val chromecast = ScanHit("192.168.1.57", 37211, ScanKind.Wireless, "chromecast", "Google")
    private val unknown = ScanHit("192.168.1.88", 5555, ScanKind.Classic, null, null)

    /**
     * [screen] captures every window, so a dialog shows over its scrim. Robolectric doesn't apply
     * the platform dialog width, so dialogs still render edge to edge here; on a phone the
     * default `DialogProperties` give them the usual margins.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String, dark: Boolean = false, screen: Boolean = false, before: () -> Unit = {}, content: @Composable () -> Unit) {
        // Expressive indicators animate forever, so the clock never idles on its own:
        // drive it by hand and capture a fixed frame.
        compose.mainClock.autoAdvance = false
        compose.setContent { TellyscopeTheme(dark = dark, dynamic = false) { content() } }
        compose.mainClock.advanceTimeBy(1_000)
        before()
        compose.mainClock.advanceTimeBy(1_000)
        val path = "build/outputs/roborazzi/${name}${if (dark) "_dark" else ""}.png"
        if (screen) captureScreenRoboImage(path) else compose.onRoot().captureRoboImage(path)
    }

    private fun scan(ui: ScanUi, dark: Boolean = false, name: String) = shot(name, dark) {
        ScanScreen(ui, now, {}, {}, { _, _ -> }, {}, { null })
    }

    @Test fun scan_inProgress() = scan(inProgress, name = "01_scan_in_progress")

    private val connectStates get() = ScanUi(
        scanning = false,
        progress = ScanProgress(254, 254, "192.168.1.0/24"),
        hits = listOf(xgimi, chromecast, unknown),
        rows = mapOf(
            xgimi.ip to RowState.Waiting(now - 12_000),
            chromecast.ip to RowState.Code("4821"),
            unknown.ip to RowState.NotAllowed,
        ),
    )

    @Test fun scan_connectStates() = scan(connectStates, name = "02_scan_connect_states")

    @Test fun scan_connectStates_dark() = scan(connectStates, dark = true, name = "02_scan_connect_states")

    private val inProgress get() = ScanUi(
        scanning = true,
        progress = ScanProgress(115, 254, "192.168.1.0/24"),
        hits = listOf(xgimi, box),
        rows = mapOf(xgimi.ip to RowState.Found, box.ip to RowState.Found),
    )

    @Test fun scan_inProgress_dark() = scan(inProgress, dark = true, name = "01_scan_in_progress")

    private val empty = ScanUi(scanning = true, progress = ScanProgress(40, 254, "192.168.1.0/24"))

    @Test fun scan_empty() = scan(empty, name = "03_scan_empty_guide")

    @Test fun scan_empty_dark() = scan(empty, dark = true, name = "03_scan_empty_guide")

    @Test fun scan_otherGuide() = shot("03_scan_other_guide", before = { compose.onNodeWithText("Other").performClick() }) {
        ScanScreen(empty, now, {}, {}, { _, _ -> }, {}, { null })
    }

    private val key = "3F:A2:91:0C:7B:E4:58:D6:21:9A:C3:4F:80:1E:B7:65"

    private fun connect(hit: ScanHit, row: RowState, dark: Boolean, name: String) = shot(name, dark) {
        ConnectScreen(hit, row, now, brands.first(), key, {}, {}, {}, {})
    }

    @Test fun connect_code() = connect(chromecast, RowState.Code("4821"), false, "03_connect_code")

    @Test fun connect_code_dark() = connect(chromecast, RowState.Code("4821"), true, "03_connect_code")

    @Test fun connect_allow() = connect(xgimi, RowState.Waiting(now - 12_000), false, "03_connect_allow")

    @Test fun connect_allow_dark() = connect(xgimi, RowState.Waiting(now - 12_000), true, "03_connect_allow")

    @Test fun connect_notAllowed() = connect(unknown, RowState.NotAllowed, false, "03_connect_no_answer")

    @Test fun connect_notAllowed_dark() = connect(unknown, RowState.NotAllowed, true, "03_connect_no_answer")

    private fun deviceState(device: DeviceUi.() -> DeviceUi = { this }): UiState = runBlocking {
        val d = Device(xgimi.ip, xgimi, DeviceState.Online, "XGIMI Horizon Pro", "XGIMI", "11", now)
        UiState(
            devices = listOf(d) + fake.known(),
            selectedId = d.id,
            device = DeviceUi(
                snapshot = fake.snapshot(d.id),
                apps = fake.apps(d.id).map { it.copy(icon = sampleIcons[it.packageName]) },
                network = fake.network(d.id),
                lists = lists(d.id),
            ).device(),
            now = now,
        )
    }

    /** The fake's list, among a few other models so "Which TV is this?" has a choice. */
    private suspend fun lists(id: String): Suggestions.Ready {
        val ready = fake.suggestions(id) { fake.apps(id) } as Suggestions.Ready
        val others = listOf(
            "XGIMI MoGo 2 projector", "XGIMI MoGo 3 Pro projector", "Philips 55OLED708",
            "Sony Bravia (Google TV)", "TCL C-series", "Xiaomi Mi Box S",
        ).map {
            val slug = it.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            ListEntry(slug, it, null, 30, "devices/$slug.json", "0".repeat(64))
        } + ListEntry("google-chromecast-gtv-4k", "Chromecast with Google TV 4K", null, 34, "devices/google-chromecast-gtv-4k.json", "0".repeat(64))
        return ready.copy(devices = ready.devices + others)
    }

    // Bottom-navigation mock-ups: the TV page as four tabs, light and dark, plus both app-bar menus.
    private fun tabsState(): UiState {
        val history = listOf(
            HistoryEntry(2, xgimi.ip, now - 40_000, Action.Disable, "com.xgimi.ota", "XGIMI Updates", Action.Enable, null),
            HistoryEntry(1, xgimi.ip, now - 5 * 60_000, Action.Stop, "com.netflix.ninja", "Netflix", Action.Open, now - 4 * 60_000),
        )
        val files = listOf(
            TvFile("subtitles.srt", 61_000, now - 40 * 60_000L),
            TvFile("kodi-21.1-armeabi-v7a.apk", 74_000_000, now - 5 * 3_600_000L),
            TvFile("holiday-2025.mp4", 812_000_000, now - 3 * 86_400_000L),
        )
        val tweaks = TvTweaks(dnsHost = TvSettings.ADGUARD, animationScale = null)
        return deviceState { copy(history = history, files = FilesUi(TvFolder.DOWNLOAD, files), tweaks = tweaks) }
    }

    // The 0.5.0 Overview: three TVs, one offline with saved facts.
    private fun md3State(): UiState {
        val s = tabsState()
        val sel = s.selected!!.copy(nickname = "Projector")
        val cast = Device(chromecast.ip, chromecast, DeviceState.Online, "Chromecast", "Google", "12", now, nickname = "Bedroom")
        val boxTv = Device(box.ip, box, DeviceState.Offline, "X96 Max", "Amlogic", "9", now - 2 * 3_600_000L, nickname = "Kitchen",
            lastKnown = LastKnown("Amlogic", null, "9", java.time.YearMonth.of(2021, 3), 9.6, 14.5))
        return s.copy(devices = listOf(sel, cast, boxTv))
    }
    private fun md3(name: String, dark: Boolean, state: () -> UiState = ::md3State) =
        shot(name, dark) { DeviceTabsScreen(state(), DeviceActions()) }
    @Test fun md3_overview() = md3("60_md3_overview", false)
    @Test fun md3_overview_dark() = md3("60_md3_overview", true)
    @Test fun md3_offline() = md3("62_md3_offline", false) { md3State().let { it.copy(selectedId = it.devices[2].id, device = DeviceUi(history = it.device.history)) } }
    @Test fun md3_offline_dark() = md3("62_md3_offline", true) { md3State().let { it.copy(selectedId = it.devices[2].id, device = DeviceUi(history = it.device.history)) } }

    /**
     * Scrolled past the hero: it has collapsed into the bar, with the ⋮ menu still in place. On a
     * phone-height screen, so each tab has more than a screenful to scroll.
     */
    private fun collapsed(name: String, dark: Boolean, badge: String, tab: TvTab = TvTab.Overview, state: () -> UiState = ::md3State) {
        val scroll = { compose.onNode(hasScrollAction()).performScrollToIndex(1); Unit }
        shot(name, dark, before = scroll) { DeviceTabsScreen(state(), DeviceActions(), startTab = tab) }
        compose.onAllNodesWithText(badge).onFirst().assertExists()
        compose.onNodeWithContentDescription("More options").assertExists()
        // The chips scrolled away, or were never there. The list keeps items just off screen composed, so not shown is the check.
        val chips = compose.onAllNodesWithText("Device")
        repeat(chips.fetchSemanticsNodes().size) { chips[it].assertIsNotDisplayed() }
    }

    private fun offlineMd3() = md3State().let { it.copy(selectedId = it.devices[2].id, device = DeviceUi(history = it.device.history)) }

    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_collapsed() = collapsed("61_md3_collapsed", false, "Connected")
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_collapsed_dark() = collapsed("61_md3_collapsed", true, "Connected")
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_offline_collapsed() = collapsed("62_md3_offline_collapsed", false, "Offline", state = ::offlineMd3)
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_offline_collapsed_dark() = collapsed("62_md3_offline_collapsed", true, "Offline", state = ::offlineMd3)
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_apps_collapsed() = collapsed("64_md3_apps_collapsed", false, "Connected", TvTab.Apps)
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_apps_collapsed_dark() = collapsed("64_md3_apps_collapsed", true, "Connected", TvTab.Apps)
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_files_collapsed() = collapsed("65_md3_files_collapsed", false, "Connected", TvTab.Files)
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_files_collapsed_dark() = collapsed("65_md3_files_collapsed", true, "Connected", TvTab.Files)
    // On a tall screen the short tabs still fold the hero fully into the bar: the end space makes the room.
    @Test fun md3_files_foldsOnTallScreen() = collapsed("65_md3_files_collapsed_tall", false, "Connected", TvTab.Files)
    @Test fun md3_tweaks_foldsOnTallScreen() = collapsed("66_md3_tweaks_collapsed_tall", false, "Connected", TvTab.Tweaks)
    // Download and Movies are tabs under the hero now, like Apps.
    private fun moviesState() = md3State().let { it.copy(device = it.device.copy(files = FilesUi(TvFolder.MOVIES, listOf(
        TvFile("holiday-2025.mp4", 812_000_000, now - 3 * 86_400_000L),
        TvFile("family-trip-2025.mp4", 486_000_000, now - 9 * 86_400_000L),
    )))) }
    // The end space is Files' own: Overview, long after it on a phone-height screen, ends with none.
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun longTabAfterShortOneHasNoEndSpace() {
        compose.mainClock.autoAdvance = false
        compose.setContent { TellyscopeTheme(dynamic = false) { DeviceTabsScreen(md3State(), DeviceActions(), startTab = TvTab.Files) } }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(compose.onNodeWithTag("tail").fetchSemanticsNode().size.height > 0)
        compose.onNodeWithText("Overview").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // By key: a space of no height never counts as displayed, so scrolling to the node would never stop.
        compose.onNode(hasScrollAction()).performScrollToKey("tail")
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0, compose.onNodeWithTag("tail").fetchSemanticsNode().size.height)
    }
    private fun emptyFiles() = md3State().let { it.copy(device = it.device.copy(files = FilesUi(TvFolder.DOWNLOAD, emptyList()))) }
    @Test fun md3_files_empty() {
        shot("76_md3_files_empty") { DeviceTabsScreen(emptyFiles(), DeviceActions(), startTab = TvTab.Files) }
        compose.onNodeWithText("Nothing in Download yet").assertIsDisplayed()
        // The save hint is only for files there are.
        compose.onNodeWithText("Tap a file to save a copy on your phone.").assertDoesNotExist()
    }
    @Test fun md3_files_empty_dark() { shot("76_md3_files_empty", dark = true) { DeviceTabsScreen(emptyFiles(), DeviceActions(), startTab = TvTab.Files) } }
    // Part way through the fold: the cookie and name on their way to the bar, drawn once, over both.
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_apps_folding() {
        val part = { compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 150f) }; Unit }
        shot("77_md3_apps_folding", before = part) { DeviceTabsScreen(md3State(), DeviceActions(), startTab = TvTab.Apps) }
        // The bar's own title stays out of the tree until the hero has fully folded.
        compose.onNodeWithText("Connected").assertDoesNotExist()
    }
    @Test fun md3_files_movies() { shot("73_md3_files_movies") { DeviceTabsScreen(moviesState(), DeviceActions(), startTab = TvTab.Files) } }
    @Test fun md3_files_movies_dark() { shot("73_md3_files_movies", dark = true) { DeviceTabsScreen(moviesState(), DeviceActions(), startTab = TvTab.Files) } }
    @Test fun md3_files_offline() {
        shot("74_md3_files_offline") { DeviceTabsScreen(offlineMd3(), DeviceActions(), startTab = TvTab.Files) }
        compose.onNodeWithText("Movies").assertExists()
        compose.onNodeWithText("Connect to the TV to see its files.").assertExists()
    }
    @Test fun md3_files_offline_dark() { shot("74_md3_files_offline", dark = true) { DeviceTabsScreen(offlineMd3(), DeviceActions(), startTab = TvTab.Files) } }
    @Test fun filesTabsPickTheFolder() {
        val picked = mutableListOf<TvFolder>()
        compose.mainClock.autoAdvance = false
        compose.setContent { TellyscopeTheme(dynamic = false) { DeviceTabsScreen(md3State(), DeviceActions(folder = { picked += it }), startTab = TvTab.Files) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Movies").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(TvFolder.MOVIES), picked)
    }
    @Test fun md3_tweaks() { shot("66_md3_tweaks") { DeviceTabsScreen(md3State(), DeviceActions(), startTab = TvTab.Tweaks) } }
    @Test fun md3_tweaks_dark() { shot("66_md3_tweaks", dark = true) { DeviceTabsScreen(md3State(), DeviceActions(), startTab = TvTab.Tweaks) } }
    @Test fun md3_tweaks_offline() { shot("75_md3_tweaks_offline") { DeviceTabsScreen(offlineMd3(), DeviceActions(), startTab = TvTab.Tweaks) } }
    @Test fun md3_tweaks_offline_dark() { shot("75_md3_tweaks_offline", dark = true) { DeviceTabsScreen(offlineMd3(), DeviceActions(), startTab = TvTab.Tweaks) } }
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_tweaks_collapsed_dark() = collapsed("66_md3_tweaks_collapsed", true, "Connected", TvTab.Tweaks)
    // "See changes" on Overview opens History, and Tweaks no longer lists the changes.
    @Test fun seeChangesOpensHistory() {
        var opened = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { TellyscopeTheme(dynamic = false) { DeviceTabsScreen(md3State(), DeviceActions(history = { opened++ })) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("See changes"))
        compose.onNodeWithText("See changes").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, opened)
    }
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun md3_tweaks_collapsed() = collapsed("66_md3_tweaks_collapsed", false, "Connected", TvTab.Tweaks)

    @Test fun md3_expandedHasNoBarTitle() {
        md3("60_md3_overview", false)
        // At rest the bar holds only ⋮; the hero's pill says how the TV is doing instead.
        compose.onNodeWithText("Connected").assertDoesNotExist()
    }
    // The tabs share one list: a tab opened after another was scrolled starts at the top, hero unfolded.
    @Config(qualifiers = "w411dp-h640dp-xxhdpi")
    @Test fun tabSwitchOpensAtTop() {
        compose.mainClock.autoAdvance = false
        compose.setContent { TellyscopeTheme(dynamic = false) { DeviceTabsScreen(md3State(), DeviceActions(), startTab = TvTab.Apps) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(hasScrollAction()).performScrollToIndex(1)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithText("Connected").onFirst().assertExists()
        compose.onNodeWithText("Files").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Connected").assertDoesNotExist()
    }
    @Test fun md3_apps() { shot("64_md3_apps") { DeviceTabsScreen(md3State(), DeviceActions(), startTab = TvTab.Apps) } }
    @Test fun md3_apps_dark() { shot("64_md3_apps", dark = true) { DeviceTabsScreen(md3State(), DeviceActions(), startTab = TvTab.Apps) } }
    // The Apps tab's own tabs: what came with the TV, and only what the user installed.
    private fun appsTab(name: String, dark: Boolean, label: String, state: () -> UiState = ::md3State) =
        shot(name, dark, before = { compose.onNodeWithText(label).performClick(); Unit }) { DeviceTabsScreen(state(), DeviceActions(), startTab = TvTab.Apps) }
    @Test fun md3_apps_installed() = appsTab("69_md3_apps_installed", false, "Installed")
    @Test fun md3_apps_installed_dark() = appsTab("69_md3_apps_installed", true, "Installed")
    @Test fun md3_apps_builtin() = appsTab("70_md3_apps_builtin", false, "Built-in")
    @Test fun md3_apps_builtin_dark() = appsTab("70_md3_apps_builtin", true, "Built-in")
    @Test fun md3_apps_offline() {
        shot("71_md3_apps_offline") { DeviceTabsScreen(offlineMd3(), DeviceActions(), startTab = TvTab.Apps) }
        compose.onNodeWithText(Copy.RUNNING_OFFLINE).assertExists()
    }
    @Test fun md3_apps_offline_dark() { shot("71_md3_apps_offline", dark = true) { DeviceTabsScreen(offlineMd3(), DeviceActions(), startTab = TvTab.Apps) } }
    // Just opened: the list is still on its way, so Apps says so rather than "Nothing here yet."
    private fun appsLoading() = md3State().let { it.copy(device = it.device.copy(apps = emptyList(), appsLoading = true)) }
    @Test fun md3_apps_loading() {
        shot("78_md3_apps_loading") { DeviceTabsScreen(appsLoading(), DeviceActions(), startTab = TvTab.Apps) }
        compose.onNodeWithText(Copy.READING_APPS).assertExists()
        compose.onNodeWithText(Copy.TV_OFFLINE).assertDoesNotExist()
    }
    @Test fun md3_apps_loading_dark() { shot("78_md3_apps_loading", dark = true) { DeviceTabsScreen(appsLoading(), DeviceActions(), startTab = TvTab.Apps) } }
    // Folded, the System header stays with its count and the protected apps go.
    private fun foldedBuiltIn() = md3State().copy(foldedSystem = setOf(AppsTab.BuiltIn))
    @Test fun md3_apps_builtin_folded() = appsTab("72_md3_apps_builtin_folded", false, "Built-in", ::foldedBuiltIn)
    @Test fun md3_apps_builtin_folded_dark() {
        appsTab("72_md3_apps_builtin_folded", true, "Built-in", ::foldedBuiltIn)
        compose.onNodeWithText("System UI").assertDoesNotExist()
    }
    @Test fun systemHeaderFoldsItsTab() {
        var folded: Pair<AppsTab, Boolean>? = null
        compose.mainClock.autoAdvance = false
        compose.setContent { TellyscopeTheme(dynamic = false) { DeviceTabsScreen(md3State(), DeviceActions(foldSystem = { t, f -> folded = t to f }), startTab = TvTab.Apps) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Built-in").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(Copy.SYSTEM_WHY))
        compose.onNodeWithText(Copy.SYSTEM_WHY).performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(AppsTab.BuiltIn to true, folded)
    }
    @Test fun md3_files() { shot("65_md3_files") { DeviceTabsScreen(md3State(), DeviceActions(), startTab = TvTab.Files) } }
    @Test fun md3_files_dark() { shot("65_md3_files", dark = true) { DeviceTabsScreen(md3State(), DeviceActions(), startTab = TvTab.Files) } }
    // Nothing came back: the TV still has off what Tellyscope turned off.
    private fun md3Calm() = md3State().let { st -> st.copy(device = st.device.copy(apps = st.device.apps.map { if (it.packageName == "com.xgimi.ota") it.copy(enabled = false) else it })) }
    @Test fun md3_calm() = md3("67_md3_calm", false, state = ::md3Calm)
    @Test fun md3_calm_dark() = md3("67_md3_calm", true, state = ::md3Calm)
    // Nothing left to do: the list's apps are all off and none came back, so no Debloat button.
    // Everything on the list is off, so Debloat turns tonal and leads to Apps.
    private val listed = setOf("com.xgimi.assistant", "com.google.android.tvrecommendations", "com.netflix.ninja")
    private fun md3Clean() = md3Calm().let { st -> st.copy(device = st.device.copy(apps = st.device.apps.map { if (it.packageName in listed) it.copy(enabled = false) else it })) }
    @Test fun md3_clean() = md3("68_md3_clean", false, state = ::md3Clean)
    @Test fun md3_clean_dark() = md3("68_md3_clean", true, state = ::md3Clean)
    // An offline TV's chip still switches to it: its page shows the saved facts and Reconnect.
    @Test fun offlineChipSelects() {
        val state = md3State()
        var picked: String? = null
        compose.setContent { TellyscopeTheme(dynamic = false) { DeviceTabsScreen(state, DeviceActions(select = { picked = it })) } }
        compose.onNodeWithText("Kitchen").performClick()
        assertEquals(state.devices[2].id, picked)
    }
    // After a restart there's no snapshot: the saved storage still shows, as of the last visit.
    @Test fun offlineAfterRestartShowsSavedStorage() {
        val state = md3State().let { it.copy(selectedId = it.devices[2].id, device = DeviceUi()) }
        compose.setContent { TellyscopeTheme(dynamic = false) { DeviceTabsScreen(state, DeviceActions()) } }
        compose.onNodeWithText("As of 2 h ago").assertExists()
        compose.onNodeWithText("4.9 GB free").assertExists()
    }
    @Test fun md3_oneTv() = md3("63_md3_one_tv", false) { md3State().let { it.copy(devices = it.devices.take(1)) } }
    @Test fun md3_oneTv_dark() = md3("63_md3_one_tv", true) { md3State().let { it.copy(devices = it.devices.take(1)) } }

    private fun tabShots(tab: TvTab, name: String) {
        shot(name) { DeviceTabsScreen(tabsState(), DeviceActions(), startTab = tab) }
    }

    @Test fun tabs_overview() = tabShots(TvTab.Overview, "40_tabs_overview")
    @Test fun tabs_apps() = tabShots(TvTab.Apps, "41_tabs_apps")
    @Test fun tabs_files() = tabShots(TvTab.Files, "42_tabs_files")
    @Test fun tabs_tweaks() = tabShots(TvTab.Tweaks, "43_tabs_tweaks")
    @Test fun tabs_overview_dark() = shot("40_tabs_overview", dark = true) { DeviceTabsScreen(tabsState(), DeviceActions(), startTab = TvTab.Overview) }
    @Test fun tabs_apps_dark() = shot("41_tabs_apps", dark = true) { DeviceTabsScreen(tabsState(), DeviceActions(), startTab = TvTab.Apps) }
    @Test fun tabs_files_dark() = shot("42_tabs_files", dark = true) { DeviceTabsScreen(tabsState(), DeviceActions(), startTab = TvTab.Files) }
    @Test fun tabs_tweaks_dark() = shot("43_tabs_tweaks", dark = true) { DeviceTabsScreen(tabsState(), DeviceActions(), startTab = TvTab.Tweaks) }

    @Test fun tabs_appMenu() = appMenu(dark = false)
    @Test fun tabs_appMenu_dark() = appMenu(dark = true)

    @Test fun tabs_searchOnlyOnApps() {
        val queries = mutableListOf<String>()
        compose.mainClock.autoAdvance = false // as in shot(): expressive indicators never idle
        compose.setContent {
            TellyscopeTheme(dark = false, dynamic = false) {
                DeviceTabsScreen(tabsState(), DeviceActions(query = { queries += it }), startTab = TvTab.Apps)
            }
        }
        compose.onNodeWithText("Search apps").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Overview").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // Leaving Apps closed the search and cleared its query; the field is gone with it.
        assertEquals(listOf(""), queries)
        compose.onNodeWithText("Search apps").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close search").assertDoesNotExist()
        compose.onNodeWithText("Apps").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Search apps").assertExists()
    }

    @Test fun tabs_searchHidesTheTvAndBackBringsItBack() {
        val state = tabsState()
        val tv = name(state.selected!!)
        lateinit var back: OnBackPressedDispatcher
        compose.mainClock.autoAdvance = false
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            TellyscopeTheme(dark = false, dynamic = false) { DeviceTabsScreen(state, DeviceActions(), startTab = TvTab.Apps) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val shown = { compose.onAllNodesWithText(tv).fetchSemanticsNodes().size }
        val atRest = shown()
        assertTrue(atRest > 0)
        for (close in listOf({ compose.runOnUiThread { back.onBackPressed() } }, { compose.onNodeWithContentDescription("Close search").performClick() })) {
            compose.onNodeWithText("Search apps").performClick()
            compose.mainClock.advanceTimeBy(1_000)
            // The field has the top, and the TV and the buttons are gone.
            compose.onNodeWithContentDescription("Close search").assertExists()
            compose.onNodeWithText("Installed").assertDoesNotExist()
            assertEquals(0, shown())
            close()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithContentDescription("Close search").assertDoesNotExist()
            assertEquals(atRest, shown())
        }
    }

    @Test fun tabs_searchOpen() = searchOpen(dark = false)
    @Test fun tabs_searchOpen_dark() = searchOpen(dark = true)

    private fun searchOpen(dark: Boolean) {
        val open = { compose.onNodeWithText("Search apps").performClick(); Unit }
        shot("80_apps_search", dark, before = open) { DeviceTabsScreen(tabsState().copy(query = "xgimi"), DeviceActions(), startTab = TvTab.Apps) }
    }

    @Test fun tabs_searchFindsAppsUnderEveryButton() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TellyscopeTheme(dark = false, dynamic = false) {
                DeviceTabsScreen(tabsState().copy(query = "google"), DeviceActions(), startTab = TvTab.Apps)
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Search apps").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // YouTube is only under Installed and Google Play services only under Built-in; one list has both.
        compose.onNodeWithText("YouTube").assertExists()
        compose.onNodeWithText("Google Play services").assertExists()
        compose.onNodeWithText("System", substring = true).assertDoesNotExist()
    }

    /** Opens ⋮ on a fresh screen, checks both blocks are there, and taps [item]; returns the actions it called. */
    private fun pickFromMenu(item: String): List<String> {
        val calls = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TellyscopeTheme(dark = false, dynamic = false) {
                DeviceTabsScreen(tabsState(), DeviceActions(advanced = { calls += "advanced" }, settings = { calls += "settings" }, history = { calls += "history" }, reset = { calls += "reset" }))
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // Every group is there: this TV's items, the app's, then Forget. Rename, Forget and About open dialogs.
        for (i in listOf("Rename this TV", Copy.HISTORY, Copy.ADVANCED_MONITOR, "Settings", Copy.SHARE, "About & licences", Copy.RESET_TV, "Forget this TV")) compose.onNodeWithText(i).assertHasClickAction()
        compose.onNodeWithText(item).performClick()
        compose.mainClock.advanceTimeBy(1_000)
        return calls
    }

    @Test fun tabs_menuAdvancedOpensAdvanced() = assertEquals(listOf("advanced"), pickFromMenu(Copy.ADVANCED_MONITOR))
    @Test fun tabs_menuHistoryOpensHistory() = assertEquals(listOf("history"), pickFromMenu(Copy.HISTORY))
    @Test fun tabs_menuSettingsOpensSettings() = assertEquals(listOf("settings"), pickFromMenu("Settings"))
    @Test fun tabs_menuShareOpensShareSheet() {
        assertEquals(emptyList<String>(), pickFromMenu(Copy.SHARE))
        assertSharedTellyscope()
    }

    /** The last activity started is Android's share sheet around a plain-text send of [SHARE_TEXT]. */
    private fun assertSharedTellyscope() {
        val chooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(SHARE_TEXT, send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(SHARE_TEXT.endsWith(RELEASES.removeSuffix("/releases")))
    }

    @Test fun tabs_menuResetOpensReset() = assertEquals(listOf("reset"), pickFromMenu(Copy.RESET_TV))

    private fun settings(opened: MutableList<String> = mutableListOf()) = @Composable {
        val links = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        CompositionLocalProvider(LocalUriHandler provides links) {
            SettingsScreen(null, onListSource = {}, checkUpdates = true, alphaBuild = false, onCheckUpdates = {}, back = {})
        }
    }

    @Test fun settings_advancedFoldedAndLinks() {
        val opened = mutableListOf<String>()
        shot("81_settings") { settings(opened)() }
        // Check for updates leads; Advanced starts folded.
        compose.onNodeWithText("Check for updates").assertExists()
        compose.onNodeWithText("Use your own debloat lists").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("cleanyourtv").assertExists()
        compose.onNodeWithText(" 40k+").assertExists()
        compose.onNodeWithText("cleanyourtv").performClick()
        compose.onNodeWithText("How to use your own lists").performClick()
        assertEquals(listOf("https://github.com/Gathin23/cleanyourtv", RELEASES.removeSuffix("/releases") + "/blob/main/CONTRIBUTING.md#using-your-own-lists"), opened)
        assertTrue(opened[1].startsWith("https://github.com/") && opened[1].contains("/tellyscope/blob/main/CONTRIBUTING.md"))
        // It opens the existing source dialog, unchanged.
        compose.onNodeWithText("Use your own debloat lists").assertHasClickAction()
    }

    @Test fun settings_shareOpensShareSheet() {
        compose.setContent { TellyscopeTheme(dark = false, dynamic = false) { settings()() } }
        compose.onNodeWithText(Copy.SHARE).performClick()
        assertSharedTellyscope()
    }

    @Test fun settings_dark() = shot("81_settings", dark = true) { settings()() }
    @Test fun settings_advanced() = shot("82_settings_advanced", before = { compose.onNodeWithText("Advanced").performClick(); Unit }) { settings()() }
    @Test fun settings_advanced_dark() = shot("82_settings_advanced", dark = true, before = { compose.onNodeWithText("Advanced").performClick(); Unit }) { settings()() }

    private fun appMenu(dark: Boolean) {
        val open = {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("About & licences").assertExists()
            Unit
        }
        shot("45_tabs_app_menu", dark, screen = true, before = open) { DeviceTabsScreen(tabsState(), DeviceActions()) }
    }

    // Someone else connected: red on ⋮ and on Who has access, and History keeps its own dot.
    private fun appMenuWarning(dark: Boolean) {
        val other = TvAccess(listOf(app.tellyscope.data.AccessClient("192.0.2.47", 1, app.tellyscope.data.AccessClient.Who.Other)), emptyList())
        val open = { compose.onNodeWithContentDescription("More options").performClick(); compose.mainClock.advanceTimeBy(1_000); Unit }
        shot("45_tabs_app_menu_warning", dark, screen = true, before = open) { DeviceTabsScreen(tabsState().let { it.copy(device = it.device.copy(access = other)) }, DeviceActions()) }
        compose.onNodeWithText(Copy.othersConnected(1)).assertExists()
    }
    @Test fun tabs_appMenuWarning() = appMenuWarning(false)
    @Test fun tabs_appMenuWarning_dark() = appMenuWarning(true)

    // ⋮'s dot is for a change newer than the last look at History, and goes once History has seen it.
    @Test fun historyDotUntilSeen() {
        val s = tabsState()
        assertTrue(s.historyNew)
        val newest = s.device.history.maxOf { it.at }
        assertFalse(s.copy(historySeen = mapOf(s.selectedId!! to newest)).historyNew)
        assertTrue(s.copy(historySeen = mapOf(s.selectedId!! to newest - 1)).historyNew)
        assertFalse(s.copy(device = s.device.copy(history = emptyList())).historyNew)
    }

    private fun history(name: String, dark: Boolean, state: () -> UiState) = shot(name, dark) { HistoryScreen(state(), {}, {}, {}) }
    @Test fun history_list() = history("46_history", false, ::tabsState)
    @Test fun history_list_dark() = history("46_history", true, ::tabsState)
    @Test fun history_restoring() = history("47_history_restoring", false) { tabsState().let { it.copy(device = it.device.copy(busy = RESTORING)) } }
    @Test fun history_restoring_dark() = history("47_history_restoring", true) { tabsState().let { it.copy(device = it.device.copy(busy = RESTORING)) } }
    @Test fun history_empty() {
        history("48_history_empty", false) { deviceState() }
        compose.onNodeWithText("Nothing changed yet").assertExists()
    }
    @Test fun history_empty_dark() = history("48_history_empty", true) { deviceState() }

    // Reset TV, as the frames drew it: XGIMI with 14 apps to turn on, 3 to bring back and Home to move.
    private val resetPlan = ResetPlan(
        turnOn = listOf(
            "YouTube Kids" to "com.google.android.youtube.tvkids", "Google Play Movies & TV" to "com.google.android.videos",
            "Google Play Games" to "com.google.android.play.games", "Live Channels" to "com.google.android.tv",
            "Sports" to "com.google.android.tvrecommendations.sports", "XGIMI Assistant" to "com.xgimi.assistant",
            "Netflix" to "com.netflix.ninja", "XGIMI Updates" to "com.xgimi.ota", "Google Play Music" to "com.google.android.music",
            "Prime Video" to "com.amazon.amazonvideo.livingroom", "Google Play Store" to "com.android.vending",
            "Disney+" to "com.disney.disneyplus", "XGIMI Manual" to "com.xgimi.manual", "Screensaver" to "com.google.android.backdrop",
        ).map { (label, pkg) -> ResetApp(pkg, label) },
        bringBack = listOf(ResetApp("com.google.android.katniss", "Google app for Android TV"), ResetApp("com.xgimi.store", "XGIMI Store"), ResetApp("com.google.android.tvrecommendations", "TV Recommendations")),
        home = ResetHome("com.spocky.projengmenu", "Projectivy Launcher", "com.google.android.apps.tv.launcherx", "Google TV Home", service = true),
        shippedOff = 4,
        ownHome = "Google TV Home",
    )
    private fun reset(name: String, dark: Boolean, ui: ResetUi, before: () -> Unit = {}) =
        shot(name, dark, before = before) { ResetScreen(deviceState { copy(reset = ui) }, ResetActions()) }
    private val sportsFailed = listOf(ResetFailure(ResetPart.TurnOn, resetPlan.turnOn[4], "the TV didn't answer in time"))

    @Test fun reset_found() {
        reset("a1_reset_found", false, ResetUi(plan = resetPlan))
        compose.onNodeWithText(Copy.resetFound(18)).assertExists()
        compose.onNodeWithText(Copy.RESET_BUTTON).assertHasClickAction()
    }
    @Test fun reset_found_dark() = reset("a1_reset_found", true, ResetUi(plan = resetPlan))
    @Test fun reset_listOpened() {
        reset("a2_reset_list", false, ResetUi(plan = resetPlan)) { compose.onNodeWithText(Copy.turnOnApps(14)).performClick(); compose.mainClock.advanceTimeBy(1_000) }
        // Seven of fourteen, then the rest behind Show all.
        compose.onNodeWithText("com.netflix.ninja").assertExists()
        compose.onNodeWithText("com.xgimi.ota").assertDoesNotExist()
        compose.onNodeWithText(Copy.showAll(14)).assertExists()
    }
    @Test fun reset_listOpened_dark() = reset("a2_reset_list", true, ResetUi(plan = resetPlan)) { compose.onNodeWithText(Copy.turnOnApps(14)).performClick(); compose.mainClock.advanceTimeBy(1_000) }
    @Test fun reset_nothing() {
        reset("a3_reset_nothing", false, ResetUi(plan = ResetPlan(shippedOff = 4, ownHome = "Google TV Home")))
        compose.onNodeWithText(Copy.NOTHING_TO_RESET).assertExists()
        compose.onNodeWithText(Copy.RESET_BUTTON).assertDoesNotExist()
    }
    @Test fun reset_nothing_dark() = reset("a3_reset_nothing", true, ResetUi(plan = ResetPlan(shippedOff = 4, ownHome = "Google TV Home")))
    private val running = ResetUi(plan = resetPlan, running = resetPlan, progress = ResetProgress(ResetPart.TurnOn, 6, 14))
    @Test fun reset_running() {
        reset("a4_reset_running", false, running)
        compose.onNodeWithText(Copy.RESETTING).assertIsNotEnabled()
    }
    @Test fun reset_running_dark() = reset("a4_reset_running", true, running)
    @Test fun reset_done() {
        reset("a5_reset_done", false, ResetUi(plan = resetPlan, ran = true))
        compose.onNodeWithText(Copy.RESET_DONE).assertExists()
    }
    @Test fun reset_done_dark() = reset("a5_reset_done", true, ResetUi(plan = resetPlan, ran = true))
    @Test fun reset_partlyDone() {
        reset("a6_reset_partly", false, ResetUi(plan = resetPlan, ran = true, failed = sportsFailed))
        compose.onNodeWithText(Copy.turnedOn(13, 14)).assertExists()
        compose.onNodeWithText(Copy.tryAgain("Sports")).assertHasClickAction()
    }
    @Test fun reset_partlyDone_dark() = reset("a6_reset_partly", true, ResetUi(plan = resetPlan, ran = true, failed = sportsFailed))

    @Test fun device_overview() {
        shot("04_device") { DeviceTabsScreen(deviceState(), DeviceActions()) }
        // Plain words on the main page: no Details expander, no MB per app; numbers live on Advanced.
        compose.onNodeWithText("Details").assertDoesNotExist()
        compose.onNodeWithContentDescription("More options").assertExists() // Advanced is in there now
        compose.onNodeWithText("Running · ", substring = true).assertDoesNotExist()
        // The three main actions sit under the system widget, Debloat among them.
        for (t in listOf("Install app", "Debloat", "Send file")) compose.onNodeWithText(t).assertIsEnabled()
        for (t in listOf("Internal storage", "Network", "Check for updates")) compose.onNodeWithText(t).assertExists()
        // The list's card stands on its own, with no heading over it.
        compose.onNodeWithText("Suggested for this TV").assertDoesNotExist()
        compose.onNodeWithText("Updated ", substring = true).assertExists()
        // The Remote is the page's FAB, no longer a button in the top bar.
        compose.onNodeWithText("Remote", useUnmergedTree = true).assertExists()
    }

    @Test fun device_overview_dark() = shot("04_device", dark = true) { DeviceTabsScreen(deviceState(), DeviceActions()) }

    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    @Test fun device_confirmDisable() {
        val state = deviceState { copy(pending = "com.xgimi.assistant" to Action.Disable) }
        shot("05_device_confirm_disable") { DeviceTabsScreen(state, DeviceActions(), startTab = TvTab.Apps) }
    }

    @Test fun device_offline() {
        val base = deviceState()
        val off = base.devices.last()
        // The last stats this phone saw, 14 minutes before it went away.
        val last = fake.snapshot(off.id).copy(at = now - 14 * 60_000)
        shot("06_device_offline") {
            DeviceTabsScreen(base.copy(selectedId = off.id, device = DeviceUi(snapshot = last)), DeviceActions())
        }
        // Away, storage shows as it was last seen; the live numbers are dashes.
        compose.onNodeWithText("As of ", substring = true).assertExists()
        compose.onNodeWithText("Internal storage").assertExists()
        compose.onAllNodesWithText("\u2014").assertCountEquals(4)
        compose.onNodeWithText("Updated ", substring = true).assertExists()
        // No Remote while the TV is away.
        compose.onNodeWithText("Remote", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun device_storageFull() {
        val state = deviceState { copy(snapshot = snapshot?.copy(storageUsedGb = 29.1, storageTotalGb = 32.0)) }
        shot("13_device_storage_full") { DeviceTabsScreen(state, DeviceActions()) }
    }

    @Test fun device_history() {
        val history = listOf(
            HistoryEntry(2, xgimi.ip, now - 40_000, Action.Disable, "com.xgimi.ota", "XGIMI Updates", Action.Enable, null),
            HistoryEntry(1, xgimi.ip, now - 5 * 60_000, Action.Stop, "com.netflix.ninja", "Netflix", Action.Open, now - 4 * 60_000),
        )
        shot("07_device_history") { HistoryScreen(deviceState { copy(history = history) }, {}, {}, {}) }
    }

    @Test fun history_undoOfTurnOnAsksFirst() {
        val turnedOn = HistoryEntry(3, xgimi.ip, now - 20_000, Action.Enable, "com.xgimi.assistant", "XGIMI Assistant", Action.Disable, null)
        val undone = mutableListOf<HistoryEntry>()
        val openAndTap = {
            // The button says what it does, and a tap only asks.
            compose.onNodeWithText("Disable").performClick()
            Unit
        }
        shot("16_history_undo_disable", screen = true, before = openAndTap) {
            HistoryScreen(deviceState { copy(history = listOf(turnedOn)) }, onUndo = { undone += it }, onRestoreAll = {}, back = {})
        }
        compose.onNodeWithText("Disable XGIMI Assistant?").assertExists()
        assertEquals(emptyList<HistoryEntry>(), undone)
        compose.onNodeWithText("Yes, disable it").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(turnedOn), undone)
    }

    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    @Test fun apps_uninstallOnlyForUserApps() {
        val proposed = mutableListOf<Pair<String, Action>>()
        val tap = { label: String ->
            compose.onNodeWithText(label).performClick()
            compose.mainClock.advanceTimeBy(1_000)
        }
        shot("26_apps_uninstall_offered", screen = true, before = { tap("Installed"); tap("Netflix"); Unit }) {
            DeviceTabsScreen(deviceState(), DeviceActions(propose = { pkg, a -> proposed += pkg to a }), startTab = TvTab.Apps)
        }
        // Netflix was installed by the user; XGIMI Assistant came with the TV and only gets Disable.
        compose.onAllNodesWithText("Uninstall").assertCountEquals(1)
        compose.onNodeWithText("Uninstall").performClick()
        assertEquals(listOf("com.netflix.ninja" to Action.Uninstall), proposed)
        tap("Built-in"); tap("XGIMI Assistant")
        compose.onNodeWithText("Disable").assertExists()
        compose.onAllNodesWithText("Uninstall").assertCountEquals(0)
    }

    @Test fun apps_uninstallAsksInADialog() {
        val state = deviceState { copy(pending = "com.netflix.ninja" to Action.Uninstall) }
        // Netflix isn't running, so it's under Installed.
        shot("27_apps_uninstall_confirm", screen = true, before = { compose.onNodeWithText("Installed").performClick(); Unit }) { DeviceTabsScreen(state, DeviceActions(), startTab = TvTab.Apps) }
        compose.onNodeWithText("Uninstall Netflix?").assertExists()
        compose.onNodeWithText("can't be undone", substring = true).assertExists()
        compose.onNodeWithText("Yes, uninstall it").assertExists()
    }

    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    @Test fun apps_systemFoldsAway() {
        // Running opens with the protected apps under System, unfolded.
        shot("28_apps_system", screen = true) { DeviceTabsScreen(deviceState(), DeviceActions(), startTab = TvTab.Apps) }
        compose.onNodeWithText("System UI").assertExists()
    }
    // A fold the user chose hides them, header and count still there.
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    @Test fun apps_foldedSystemStaysFolded() {
        val state = deviceState().copy(foldedSystem = setOf(AppsTab.Running))
        shot("28_apps_system_folded", screen = true) { DeviceTabsScreen(state, DeviceActions(), startTab = TvTab.Apps) }
        compose.onNodeWithText("${Copy.SYSTEM} (", substring = true).assertExists()
        compose.onNodeWithText("System UI").assertDoesNotExist()
    }

    /** A real TV's app list, every app running so all of them show under Running. */
    private fun appsState(apps: List<AppInfo>): UiState {
        val base = deviceState()
        val procs = apps.mapIndexed { i, a -> Proc(3000 + i, a.packageName, a.packageName, 1.0, 64) }
        return base.copy(device = base.device.copy(snapshot = base.device.snapshot?.copy(procs = procs), apps = apps))
    }

    private val tvApps = listOf(
        "com.google.android.youtube.tv" to 0xFFE53935,
        "com.netflix.ninja" to 0xFF212121,
        "org.jellyfin.androidtv" to 0xFF7B5EA7,
        "com.example.weatherbox" to 0xFF0288D1,
        "com.xgimi.assistant" to 0xFF00897B,
    )

    /** Stand-ins for the PNGs the helper reads off the TV: no brand artwork in the repo. */
    private fun iconPng(argb: Long): ByteArray {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(argb.toInt())
            drawCircle(48f, 48f, 24f, Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true })
        }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun app(pkg: String, label: String, icon: ByteArray?) =
        AppInfo(pkg, label, null, pkg.startsWith("com.xgimi"), true, null, "", icon)

    /**
     * Generic icons for the sample apps, so device shots look like a real TV's list. Each is the
     * app's avatar colour with a plain glyph: no brand artwork in the repo, test-only.
     */
    private val sampleIcons: Map<String, ByteArray> by lazy {
        val white = Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true }
        val line = Paint(white).apply { style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
        fun icon(argb: Long, glyph: Canvas.() -> Unit): ByteArray {
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { drawColor(argb.toInt()); glyph() }
            return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        fun Canvas.squares() = listOf(26f, 52f).forEach { x -> listOf(26f, 52f).forEach { y -> drawRoundRect(x, y, x + 18f, y + 18f, 4f, 4f, white) } }
        fun Canvas.screen() { drawRoundRect(22f, 26f, 74f, 62f, 6f, 6f, line); drawLine(38f, 72f, 58f, 72f, line) }
        fun Canvas.film() {
            drawRoundRect(30f, 22f, 66f, 74f, 4f, 4f, white)
            val hole = Paint().apply { color = 0xFF141414.toInt() }
            listOf(28f, 40f, 52f, 64f).forEach { y -> drawRect(33f, y, 38f, y + 5f, hole); drawRect(58f, y, 63f, y + 5f, hole) }
        }
        fun Canvas.stack() = listOf(28f, 44f, 60f).forEach { y -> drawRoundRect(24f, y, 72f, y + 10f, 5f, 5f, white) }
        fun Canvas.mic() { drawRoundRect(38f, 20f, 58f, 52f, 10f, 10f, white); drawArc(30f, 34f, 66f, 64f, 0f, 180f, false, line); drawLine(48f, 66f, 48f, 76f, line) }
        fun Canvas.arrow() { drawLine(48f, 22f, 48f, 62f, line); drawLine(34f, 48f, 48f, 62f, line); drawLine(62f, 48f, 48f, 62f, line); drawLine(28f, 74f, 68f, 74f, line) }
        fun Canvas.star() = drawPath(
            android.graphics.Path().apply {
                (0 until 10).forEach { i ->
                    val r = if (i % 2 == 0) 28f else 12f
                    val a = Math.toRadians(i * 36.0 - 90)
                    val x = 48f + r * Math.cos(a).toFloat()
                    val y = 50f + r * Math.sin(a).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            },
            white,
        )
        fun Canvas.gear() { drawCircle(48f, 48f, 20f, line); drawCircle(48f, 48f, 6f, white) }
        fun Canvas.dots() = listOf(30f, 48f, 66f).forEach { drawCircle(it, 48f, 6f, white) }
        fun Canvas.keys() {
            drawRoundRect(20f, 32f, 76f, 66f, 6f, 6f, line)
            listOf(32f, 44f, 56f, 64f).forEach { x -> drawCircle(x, 44f, 3f, white) }
            drawLine(36f, 56f, 60f, 56f, line)
        }
        mapOf(
            "com.spocky.projengmenu" to icon(0xFF3D5AFE) { squares() },
            "com.google.android.youtube.tv" to icon(0xFFC4302B) { screen() },
            "com.netflix.ninja" to icon(0xFF141414) { film() },
            "org.jellyfin.androidtv" to icon(0xFF7B5EA7) { stack() },
            "com.xgimi.assistant" to icon(0xFF00897B) { mic() },
            "com.xgimi.ota" to icon(0xFF00838F) { arrow() },
            "com.google.android.tvrecommendations" to icon(0xFFF9A825) { star() },
            "com.android.systemui" to icon(0xFF546E7A) { dots() },
            "com.android.tv.settings" to icon(0xFF607D8B) { gear() },
            "com.google.android.gms" to icon(0xFF1E88E5) { gear() },
            "com.google.android.inputmethod.latin" to icon(0xFF5C6BC0) { keys() },
        )
    }

    @Test fun apps_helperNames() {
        val labels = listOf("YouTube", "Netflix", "Jellyfin", "Weather Box", "XGIMI Assistant")
        val apps = tvApps.zip(labels) { (pkg, color), label -> app(pkg, label, iconPng(color)) }
        shot("09_apps_helper_names") { DeviceTabsScreen(appsState(apps), DeviceActions(), startTab = TvTab.Apps) }
    }

    @Test fun apps_fallbackNames() {
        // The helper was refused: bundled names where known, a humanised id otherwise, letter avatars.
        val apps = tvApps.map { (pkg, _) -> app(pkg, AppNames.fallback(pkg), null) }
        shot("10_apps_fallback_names") { DeviceTabsScreen(appsState(apps), DeviceActions(), startTab = TvTab.Apps) }
    }

    /** The Files section's body on its own: the section is collapsed on the full page. */
    @Test fun device_files() = shot("09_device_files") {
        val files = runBlocking { fake.files(xgimi.ip, TvFolder.DOWNLOAD) }
        Surface { Column(Modifier.padding(16.dp)) { FilesBlock(FilesUi(TvFolder.DOWNLOAD, files), now, DeviceActions()) } }
    }

    @Test fun device_tweaks() = shot("16_device_tweaks") {
        val ui = DeviceUi(tweaks = TvTweaks(dnsHost = TvSettings.ADGUARD, animationScale = null))
        Surface { Column(Modifier.padding(16.dp)) { TweaksBlock(ui, DeviceActions()) } }
    }

    @Test fun device_tweaksDns() = shot("16_device_tweaks_dns", screen = true, before = {
        compose.onNodeWithText(Copy.DNS_TITLE).performClick()
        Unit
    }) {
        Surface { Column(Modifier.padding(16.dp)) { TweaksBlock(DeviceUi(tweaks = TvTweaks(null, null)), DeviceActions()) } }
    }

    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    @Test fun device_tweaksInHistory() {
        val history = listOf(
            HistoryEntry(3, xgimi.ip, now - 20_000, Action.ClearCaches, "", TvSettings.CACHES, null, null),
            HistoryEntry(2, xgimi.ip, now - 60_000, Action.Change, "ANIMATIONS", "Animations at 0.5×", Action.Change, null, mapOf("window_animation_scale" to null)),
            HistoryEntry(1, xgimi.ip, now - 90_000, Action.Change, "PRIVATE_DNS", "Private DNS set to ${TvSettings.ADGUARD}", Action.Change, null, mapOf("private_dns_mode" to "opportunistic")),
        )
        shot("16_device_tweaks_history") { DeviceTabsScreen(deviceState { copy(history = history) }, DeviceActions(), startTab = TvTab.Tweaks) }
    }

    @Test fun device_connectionRequest() = shot("17_device_request", screen = true) {
        val request = ConnectionRequest("AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89")
        DeviceTabsScreen(deviceState { copy(request = request) }, DeviceActions())
    }

    private val dir = Files.createTempDirectory("shots").toFile()
    private fun apk(name: String, bytes: Long) = File(dir, name).also { RandomAccessFile(it, "rw").use { f -> f.setLength(bytes) } }
    private val kodi = PickedApp(listOf(apk("kodi.apk", 74_000_000)), ApkInfo("org.xbmc.kodi", 2101, "21.1"), "Kodi")
    private val split = PickedApp(
        listOf(apk("base.apk", 31_000_000), apk("split_config.armeabi_v7a.apk", 9_400_000), apk("split_config.xxhdpi.apk", 1_100_000)),
        ApkInfo("com.example.player", 410, "4.1.0"),
        "Example Player",
    )

    private fun sideload(name: String, s: SideloadUi, dark: Boolean = false) =
        shot(name, dark, screen = true) { DeviceTabsScreen(deviceState { copy(sideload = s) }, DeviceActions()) }

    @Test fun sideload_new() = sideload("10_sideload_new", SideloadUi(SideloadUi.Step.Ready, split, null))

    @Test fun sideload_update() =
        sideload("10_sideload_update", SideloadUi(SideloadUi.Step.Ready, kodi, InstalledVersion(2050, "20.5")), dark = true)

    @Test fun sideload_downgrade() =
        sideload("11_sideload_downgrade", SideloadUi(SideloadUi.Step.Ready, kodi, InstalledVersion(2200, "22.0")))

    @Test fun sideload_installing() = sideload(
        "12_sideload_installing",
        SideloadUi(SideloadUi.Step.Installing, kodi, null, progress = Progress(30_000_000, 74_000_000)),
    )

    @Test fun sideload_refused() = sideload(
        "13_sideload_refused",
        SideloadUi(
            SideloadUi.Step.Failed, kodi, InstalledVersion(2050, "20.5"),
            error = Copy.installRefused(InstallRefusal.SIGNATURE, "Kodi"),
            detail = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package org.xbmc.kodi signatures do not match previously installed version; ignoring!]",
        ),
    )

    @Test fun sideload_installed() = sideload("14_sideload_installed", SideloadUi(SideloadUi.Step.Installed, kodi, null))

    @Test fun transfer() = shot("15_transfer", screen = true) {
        val t = TransferUi("holiday-2025.mp4", toTv = false, folder = TvFolder.DOWNLOAD, progress = Progress(301_000_000, 812_000_000))
        DeviceTabsScreen(deviceState { copy(transfer = t) }, DeviceActions())
    }

    @Test fun welcome() = shot("00_welcome") { WelcomeScreen(askLocalNetwork = true, {}, {}) }

    @Test fun welcome_dark() = shot("00_welcome", dark = true) { WelcomeScreen(askLocalNetwork = true, {}, {}) }

    @Test fun permission() = shot("08_permission") { PermissionScreen(denied = false, canAsk = true, {}, {}) }

    @Test fun permission_denied() = shot("08_permission_denied") { PermissionScreen(denied = true, canAsk = true, {}, {}) }

    @Test fun forget() = shot("09_forget", screen = true) { ForgetDialog("xgimi-projector", {}, {}) }

    @Test fun about() = shot("83_about", screen = true) { AboutDialog("4f1c2a9e7b3d5c60", {}, version = "0.8.0-alpha.2") }

    @Test fun scan_offWifi() = scan(
        ScanUi(scanning = false, noLan = true, hits = listOf(chromecast), rows = mapOf(chromecast.ip to RowState.Found)),
        name = "10_scan_off_wifi",
    )

    @Test fun scan_offWifiEmpty() = scan(ScanUi(noLan = true), name = "10_scan_off_wifi_empty")

    @Test fun scan_offWifiEmpty_dark() = scan(ScanUi(noLan = true), dark = true, name = "10_scan_off_wifi_empty")

    @Test fun device_notAllowed() {
        val base = deviceState()
        val off = base.devices.last().copy(state = DeviceState.NotAllowed)
        shot("12_device_not_allowed") {
            DeviceTabsScreen(base.copy(devices = base.devices.dropLast(1) + off, selectedId = off.id, device = DeviceUi()), DeviceActions())
        }
    }

    @Test fun restore() = shot("11_restore", screen = true) { RestoreDialog(2, {}, {}) }

    private val docked = listOf(Docked("com.google.android.youtube.tv", "YouTube"), Docked("com.netflix.ninja", "Netflix"))

    private fun remoteState(apps: List<Docked> = docked, restart: Restart? = null) =
        deviceState { copy(dock = apps) }.let { s -> restart?.let { s.copy(restarting = mapOf(s.selectedId!! to it)) } ?: s }

    @Test fun remote() = shot("12_remote", screen = true) { RemoteScreen(remoteState(), RemoteActions()) }

    @Test fun remote_dark() = shot("12_remote", dark = true, screen = true) { RemoteScreen(remoteState(), RemoteActions()) }

    @Test fun remoteKeysAreIconsWithSpokenNames() {
        compose.setContent { TellyscopeTheme(dynamic = false) { RemoteScreen(remoteState(), RemoteActions()) } }
        listOf("OK", "Back", "Home", "Menu", "Mute or unmute", "Volume down", "Volume up").forEach {
            compose.onNodeWithContentDescription(it).assertExists()
        }
        // Play/Pause went: the D-pad's centre pauses a video.
        compose.onNodeWithContentDescription("Play or pause").assertDoesNotExist()
        compose.onNodeWithText("OK").assertDoesNotExist()
    }

    // Screen off (0.9): the header says how to wake it, so nothing below moves; the ⋮ menu turns it back on.
    private fun screenOffState() = remoteState().let { it.copy(screenOff = setOf(it.selectedId!!)) }
    @Test fun remote_screenOff() = shot("13_remote_screen_off", screen = true) { RemoteScreen(screenOffState(), RemoteActions()) }
    @Test fun remote_screenOff_dark() = shot("13_remote_screen_off", dark = true, screen = true) { RemoteScreen(screenOffState(), RemoteActions()) }
    @Test fun remote_screenOffTip() = shot("13_remote_screen_off_tip", screen = true) { RemoteScreen(remoteState().copy(screenOffTip = true), RemoteActions()) }

    @Test fun screenOffSaysWhatATapDoes() {
        var off by mutableStateOf(false)
        compose.setContent { TellyscopeTheme(dynamic = false) { RemoteScreen(if (off) screenOffState() else remoteState(), RemoteActions()) } }
        compose.onNodeWithContentDescription(Copy.MORE).performClick()
        compose.onNodeWithText(Copy.SCREEN_OFF).assertExists()
        compose.onNodeWithText(Copy.SCREEN_ON).assertDoesNotExist()
        off = true
        compose.onNodeWithText(Copy.SCREEN_ON).assertExists()
        compose.onNodeWithText(Copy.SCREEN_OFF_WAKE, substring = true).assertExists()
    }

    @Test fun android14HasNoScreenOff() {
        val s = remoteState().let { st -> st.copy(devices = st.devices.map { if (it.id == st.selectedId) it.copy(android = "14") else it }) }
        compose.setContent { TellyscopeTheme(dynamic = false) { RemoteScreen(s, RemoteActions()) } }
        compose.onNodeWithContentDescription(Copy.MORE).performClick()
        compose.onNodeWithText(Copy.SCREEN_OFF).assertDoesNotExist()
        compose.onNodeWithText(Copy.REMOTE_RESTART).assertExists()
    }

    /** The Remote never scrolls: on a short phone the D-pad shrinks and the buttons stay on screen, three of them. */
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun remoteFitsAShortPhone() {
        val four = docked + listOf(Docked("org.jellyfin.androidtv", "Jellyfin"), Docked("com.spotify.tv.android", "Spotify"))
        compose.setContent { TellyscopeTheme(dynamic = false) { RemoteScreen(remoteState(apps = four), RemoteActions()) } }
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        listOf("OK", "Volume up", "Jellyfin").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Spotify").assertDoesNotExist()
    }

    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun remote_short() = shot("12_remote_short", screen = true) { RemoteScreen(remoteState(), RemoteActions()) }

    @Test fun remote_dockEmpty() = shot("12_remote_dock_empty", screen = true) { RemoteScreen(remoteState(apps = emptyList()), RemoteActions()) }

    /**
     * An app's own icon, for the Apps list shot only. Test resources, so it never reaches the APK; the
     * name and logo belong to its owner (see NOTICE).
     */
    private fun realIcon(pkg: String): ByteArray? = javaClass.classLoader?.getResourceAsStream("dock-icons/$pkg.png")?.use { it.readBytes() }

    @Test fun remote_dockFull() {
        val three = docked + Docked("org.jellyfin.androidtv", "Jellyfin")
        shot("12_remote_dock_full", screen = true) { RemoteScreen(remoteState(apps = three), RemoteActions()) }
        // A full row has no Remote buttons pill.
        compose.onNodeWithText(Copy.REMOTE_BUTTONS).assertDoesNotExist()
    }

    /**
     * Banner-only apps as letter tiles, in the colours the helper picks from their banners, beside
     * a real square icon and an app with no icon at all, in the Apps list.
     */
    private fun tilesState(): UiState {
        val projectivy = "com.spocky.projengmenu"
        val spotify = AppInfo("com.spotify.tv.android", "Spotify", 0xFF121212, false, true, null, "", removable = true, launchable = true, iconAccent = 0xFF1ED760)
        val mine = AppInfo("com.example.myapp", "My App", null, false, true, null, "1.0", removable = true, launchable = true)
        val s = md3State()
        val apps = s.device.apps.map { a ->
            when (a.packageName) {
                projectivy -> a.copy(iconColor = 0xFF1C120D, iconAccent = 0xFFFF6A13)
                "com.google.android.youtube.tv" -> a.copy(icon = realIcon(a.packageName))
                else -> a
            }
        } + spotify + mine
        return s.copy(device = s.device.copy(apps = apps))
    }

    @Test fun tiles_apps() = shot("13_tiles_apps") { DeviceTabsScreen(tilesState(), DeviceActions(), startTab = TvTab.Apps) }

    @Test fun tiles_apps_dark() = shot("13_tiles_apps", dark = true) { DeviceTabsScreen(tilesState(), DeviceActions(), startTab = TvTab.Apps) }

    // Holding a button opens the picker.
    @Test fun remote_dockPicker() = shot(
        "12_remote_dock_picker", screen = true,
        before = { compose.onNodeWithContentDescription("Netflix").performSemanticsAction(SemanticsActions.OnLongClick) },
    ) { RemoteScreen(remoteState(), RemoteActions()) }

    @Test fun remote_dockPicker_dark() = shot(
        "12_remote_dock_picker", dark = true, screen = true,
        before = { compose.onNodeWithContentDescription("Netflix").performSemanticsAction(SemanticsActions.OnLongClick) },
    ) { RemoteScreen(remoteState(), RemoteActions()) }

    @Test fun remote_menu() = shot("12_remote_menu", screen = true, before = { compose.onNodeWithContentDescription(Copy.MORE).performClick() }) {
        RemoteScreen(remoteState(), RemoteActions())
    }

    @Test fun theEmptyPillOpensThePicker() {
        compose.setContent { TellyscopeTheme(dynamic = false) { RemoteScreen(remoteState(apps = emptyList()), RemoteActions()) } }
        compose.onNodeWithText(Copy.REMOTE_BUTTONS).performClick()
        compose.onNodeWithText(Copy.buttonsHint(3)).assertExists()
    }

    @Test fun remote_restarting() = shot("13_remote_restarting", screen = true) {
        RemoteScreen(remoteState(restart = Restart(now - 25_000, down = true)), RemoteActions())
    }

    @Test fun remote_restartHidden() = shot("13_remote_restart_hidden", before = { compose.onNodeWithText("Hide").performClick() }) {
        RemoteScreen(remoteState(restart = Restart(now - 25_000, down = true)), RemoteActions())
    }

    @Test fun remote_restartLate() = shot("13_remote_restart_late", screen = true) {
        RemoteScreen(remoteState(restart = Restart(now - 61_000, down = true)), RemoteActions())
    }

    @Test fun remote_restartLost() = shot("13_remote_restart_lost", screen = true) {
        RemoteScreen(remoteState(restart = Restart(now - 130_000, down = true, waitFrom = now - 61_000, retried = true)), RemoteActions())
    }

    @Test fun device_restarting() {
        val state = deviceState().let { it.copy(restarting = mapOf(it.selectedId!! to Restart(now - 200_000, down = true))) }
        shot("14_device_restarting") { DeviceTabsScreen(state, DeviceActions()) }
    }

    @Test fun reboot() = shot("15_reboot", screen = true) { RebootDialog({}, {}) }

    @Test fun debloatGate() = shot("95_debloat_gate", screen = true) { DebloatGate(launcher = true, {}, {}, {}, {}) }

    @Test fun debloatGateApps() = shot("95_debloat_gate_apps", screen = true) { DebloatGate(launcher = false, {}, {}, {}, {}) }

    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    @Test fun advanced() = shot("17_advanced") { AdvancedScreen(deviceState(), back = {}) }

    @Test fun debloat_model() = debloatModel(dark = false)

    @Test fun debloat_model_dark() = debloatModel(dark = true)

    /** The TV's own list: straight to the apps, with "Not this TV?" in case it's wrong. */
    private fun debloatModel(dark: Boolean) {
        val lists = deviceState().device.lists as Suggestions.Ready
        shot("18_debloat_model", dark) { DebloatScreen(lists, {}, {}, {}, tvMakers = listOf("XGIMI")) }
        compose.onNodeWithText(lists.list.name).assertExists()
        compose.onNodeWithText("This model's list", substring = true).assertExists()
        compose.onNodeWithText("These apps will be turned off", substring = true).assertExists()
        compose.onNodeWithText("optional: tick it if you don't use it", substring = true).assertExists()
        compose.onNodeWithText("Not this TV?").assertExists()
        compose.onNodeWithText("TCL C-series").assertDoesNotExist()
        val ticked = lists.groups.flatMap { it.packages }.count { it.ticked }
        compose.onNodeWithText("Disable $ticked ${if (ticked == 1) "app" else "apps"}").assertIsEnabled()
    }

    /** No list for the model: its maker's lists merged, nothing ticked. */
    private fun makerLists(): Suggestions.Ready {
        val r = deviceState().device.lists as Suggestions.Ready
        return r.copy(
            list = r.list.copy(id = "maker:xgimi", name = "XGIMI", testedOs = null, sdk = null),
            groups = r.groups.map { g -> g.copy(packages = g.packages.map { it.copy(ticked = false) }) },
            byMaker = true,
        )
    }

    @Test fun debloat_maker() = debloatMaker(dark = false)

    @Test fun debloat_maker_dark() = debloatMaker(dark = true)

    private fun debloatMaker(dark: Boolean) {
        val lists = makerLists()
        shot("18_debloat_maker", dark) { DebloatScreen(lists, {}, {}, {}, tvMakers = listOf("XGIMI")) }
        compose.onNodeWithText("XGIMI").assertExists()
        compose.onNodeWithText("No list for this model", substring = true).assertExists()
        compose.onNodeWithText("Nothing is ticked", substring = true).assertExists()
        compose.onNodeWithText("tested on", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Not this TV?").assertExists()
        compose.onNodeWithText("Disable 0 apps").assertIsNotEnabled()
    }

    @Test fun debloat_noList() = debloatNoList(dark = false)

    @Test fun debloat_noList_dark() = debloatNoList(dark = true)

    /** Neither model nor maker has a list: the TV's own apps, to tick by hand. */
    private fun debloatNoList(dark: Boolean) {
        val s = deviceState()
        val ready = s.device.lists as Suggestions.Ready
        val tv = s.selected!!.let { it.copy(model = "TCL 55C735", manufacturer = "TCL", hit = it.hit.copy(maker = "TCL", hostname = null)) }
        var disabled = emptyList<String>()
        shot("18_debloat_none", dark) {
            DebloatScreen(Suggestions.NoList(null, ready.devices), {}, { disabled = it.map { a -> a.packageName } }, {}, tv = tv, tvApps = s.device.apps)
        }
        compose.onNodeWithText("TCL 55C735").assertExists()
        compose.onNodeWithText("No list for this TV or its maker yet").assertExists()
        compose.onNodeWithText("Not this TV?").assertExists()
        // Protected and already-off apps aren't offered.
        compose.onNodeWithText("System UI").assertDoesNotExist()
        compose.onNodeWithText("TV Recommendations").assertDoesNotExist()
        compose.onNodeWithText("Disable 0 apps").assertIsNotEnabled()
        compose.onNodeWithText("XGIMI Assistant").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Disable 1 app").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // The confirm still names the app before anything is turned off.
        compose.onNodeWithText("Disable 1 app?").assertExists()
        compose.onNodeWithText("Yes, disable them").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("com.xgimi.assistant"), disabled)
    }

    private val notThisTv = { compose.onNodeWithText("Not this TV?").performClick(); Unit }

    @Test fun debloat_notThisTv() {
        val lists = deviceState().device.lists
        shot("18_debloat_search", before = notThisTv) { DebloatScreen(lists, {}, {}, {}, tvMakers = listOf("XGIMI")) }
        // The search starts at the TV's maker, with nothing marked until a list is tapped.
        compose.onNodeWithText("XGIMI MoGo 2 projector").assertExists()
        compose.onNodeWithText("TCL C-series").assertDoesNotExist()
        compose.onNodeWithText("Use this list").assertIsNotEnabled()
    }

    @Test fun debloat_searchByBrand() {
        // Philips TVs report their factory, TPV, as manufacturer; the brand finds the list.
        val lists = makerLists()
        shot("18_debloat_search_brand", before = notThisTv) { DebloatScreen(lists, {}, {}, {}, tvMakers = listOf("Philips", "TPV")) }
        compose.onNodeWithText("Philips 55OLED708").assertExists()
        compose.onNodeWithText("No list for", substring = true).assertDoesNotExist()
    }

    @Test fun debloat_searchUnknownMaker() {
        // A maker no list names: the full list, with a line saying so, and nothing guessed.
        val lists = makerLists()
        shot("18_debloat_search_unknown", before = notThisTv) { DebloatScreen(lists, {}, {}, {}, tvMakers = listOf("TPV")) }
        compose.onNodeWithText("No list for \u201cTPV\u201d", substring = true).assertExists()
        compose.onNodeWithText("Chromecast with Google TV 4K").assertExists()
    }

    @Test fun debloat_helpAdd() {
        // No list for this TV's maker, and the TV is connected: offer to send its apps to cleanyourtv.
        val asked = mutableListOf<Unit>()
        val tv = deviceState().selected
        shot("85_debloat_help_add", before = notThisTv) { DebloatScreen(makerLists(), {}, {}, {}, tvMakers = listOf("TPV"), tv = tv, onHelpAdd = { asked += Unit }) }
        compose.onNodeWithText("Not in the list? Help add this TV").assertExists()
        compose.onNodeWithText("Help add this TV").performClick()
        assertEquals(1, asked.size)
    }

    private val reportApp = AppFacts("0.8.0", "Pixel 8, Android 16")

    private fun reportUi(kind: ReportKind, details: String = "", log: String? = null) = runBlocking {
        ReportUi(kind, reportApp, Reports.Connection.WIRELESS_DEBUGGING, tv = fake.facts(xgimi.ip), details = details, log = log)
    }

    /** Opens the form's dropdown that still says "Choose one" and picks [option]. */
    private fun choose(option: String) = {
        compose.onNodeWithText("Choose one").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(option).performClick()
        Unit
    }

    @Test fun report_problem() = reportProblem(dark = false)

    @Test fun report_problem_dark() = reportProblem(dark = true)

    /** From a failed install: the TV as facts, the dropdowns, what the user wrote, then what gets sent. */
    private fun reportProblem(dark: Boolean) {
        val r = reportUi(
            ReportKind.Problem, "Tried to install Kodi 21.2",
            log = "21:04:11 Connected to Projector\n21:04:19 Install org.xbmc.kodi 21.2: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]",
        )
        val opened = mutableListOf<String>()
        val links = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        shot("86_report_problem", dark, before = choose(Reports.Outcome.MISBEHAVED.option)) {
            CompositionLocalProvider(LocalUriHandler provides links) { ReportScreen(r, {}, {}) }
        }
        compose.onNodeWithText("XGIMI Horizon Pro (XK03H)").assertExists()
        compose.onNodeWithText(Reports.Connection.WIRELESS_DEBUGGING.option).assertExists()
        compose.onNodeWithText("Tellyscope sends nothing by itself", substring = true).assertExists()
        compose.onNodeWithText("Open GitHub").performClick()
        assertTrue(opened.single().startsWith("https://github.com/") && "template=device_report.yml" in opened.single())
    }

    @Test fun report_problemWaitsForWhatHappened() {
        shot("86_report_problem_empty") { ReportScreen(reportUi(ReportKind.Problem), {}, {}) }
        compose.onNodeWithText("Pick what happened to see the report.").assertExists()
        compose.onNodeWithText("Open GitHub").assertIsNotEnabled()
    }

    @Test fun report_newTv() = reportNewTv(dark = false)

    @Test fun report_newTv_dark() = reportNewTv(dark = true)

    private fun reportNewTv(dark: Boolean) {
        shot("87_report_new_tv", dark, before = choose(Reports.Stage.HERE_IS_MY_LIST.option)) { ReportScreen(reportUi(ReportKind.NewTv), {}, {}) }
        compose.onNodeWithText("Help add this TV").assertExists()
        compose.onNodeWithText("can go in a comment after you post", substring = true).assertExists()
    }

    private fun posted() = runBlocking { Reports.newTv(reportApp, fake.facts(xgimi.ip), Reports.Stage.HERE_IS_MY_LIST, "") }

    @Test fun report_posted() = reportPosted(dark = false)

    @Test fun report_posted_dark() = reportPosted(dark = true)

    /** Back from GitHub with apps that didn't fit: the card offering to add them. */
    private fun reportPosted(dark: Boolean) {
        val p = posted()
        val left = p.rest!!.lines().size - 1
        shot("88_report_posted", dark) { ReportScreen(reportUi(ReportKind.NewTv).copy(posted = p), {}, {}) }
        compose.onNodeWithText("Posted it? Add the rest as a comment").assertExists()
        compose.onNodeWithText("$left apps didn\u2019t fit", substring = true).assertExists()
        compose.onNodeWithText("Add the rest").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Copy and open your issue").assertExists()
    }

    @Test fun report_addRest() = addRest(dark = false)

    @Test fun report_addRest_dark() = addRest(dark = true)

    private fun addRest(dark: Boolean) {
        val p = posted()
        val opened = mutableListOf<String>()
        val links = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        var done = false
        shot("89_report_add_rest", dark) { CompositionLocalProvider(LocalUriHandler provides links) { AddRestScreen(p, {}, { done = true }) } }
        compose.onNodeWithText("Copy and open your issue").performClick()
        assertEquals(listOf(p.myIssues), opened)
        assertTrue(done)
    }

    @Test fun debloat_pickByHand() {
        val lists = makerLists()
        val picked = mutableListOf<String>()
        val pick = { notThisTv(); compose.mainClock.advanceTimeBy(1_000); compose.onNodeWithText("Philips 55OLED708").performClick(); Unit }
        shot("18_debloat_search_manual", before = pick) { DebloatScreen(lists, { picked += it }, {}, {}, tvMakers = listOf("TPV")) }
        compose.onNodeWithText("Use this list").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, picked.size)
        compose.onNodeWithText("Loading the list…").assertExists()
    }

    @Test fun debloat_review_otherAndroid() {
        // A list tested on another Android version ticks nothing, the way the list builder hands it over.
        val lists = (deviceState().device.lists as Suggestions.Ready).let { r ->
            r.copy(sdk = 28, groups = r.groups.map { g -> g.copy(packages = g.packages.map { it.copy(ticked = false) }) })
        }
        shot("19_debloat_review_other_android") { DebloatScreen(lists, {}, {}, {}) }
        compose.onNodeWithText("not this TV's Android", substring = true).assertExists()
        compose.onNodeWithText("Nothing is ticked", substring = true).assertExists()
        compose.onNodeWithText("Disable 0 apps").assertIsNotEnabled()
    }

    @Test fun disableSuggested() {
        val apps = listOf(
            Suggested("com.xgimi.assistant", "XGIMI Assistant", null, optional = false, ticked = true),
            Suggested("com.google.android.tvrecommendations", "TV Recommendations", null, optional = false, ticked = true),
        )
        shot("16_disable_suggested", screen = true) { DisableDialog(apps, {}, {}) }
        // Names, not just a count.
        compose.onNodeWithText("Disable 2 apps?").assertExists()
        for (a in apps) {
            compose.onNodeWithText(a.label).assertExists()
            compose.onNodeWithText(a.pkg).assertExists()
        }
    }
    // README shots: the same screens with a fuller sample list and history than the tests above need.

    /** A fuller sample list for the README: two groups, one optional app left unticked. */
    private fun readmeLists(): Suggestions.Ready {
        val r = deviceState().device.lists as Suggestions.Ready
        return r.copy(
            groups = listOf(
                SuggestedGroup(
                    "Google extras",
                    listOf(
                        Suggested("com.google.android.tvrecommendations", "TV Recommendations", "home screen suggestions", optional = false, ticked = true),
                        Suggested("com.google.android.videos", "Google TV", "film and TV store", optional = false, ticked = true),
                        Suggested("com.google.android.play.games", "Google Play Games", null, optional = false, ticked = true),
                    ),
                ),
                SuggestedGroup(
                    "Extras",
                    listOf(
                        Suggested("com.xgimi.assistant", "XGIMI Assistant", "voice assistant", optional = false, ticked = true),
                        Suggested("com.netflix.ninja", "Netflix", null, optional = true, ticked = false),
                    ),
                ),
            ),
        )
    }

    @Test fun readme_debloat() {
        shot("20_readme_debloat") { DebloatScreen(readmeLists(), {}, {}, {}) }
        compose.onNodeWithText("Disable 4 apps").assertIsEnabled()
    }

    @Test fun readme_confirm() {
        // Over the review step, as in the app.
        val confirm = {
            compose.onNodeWithText("Disable 4 apps").performClick()
            Unit
        }
        shot("21_readme_confirm", screen = true, before = confirm) { DebloatScreen(readmeLists(), {}, {}, {}) }
        compose.onNodeWithText("Disable 4 apps?").assertExists()
    }

    // Phone-sized, like every README shot: the History screen.
    @Test fun readme_history() {
        val history = listOf(
            HistoryEntry(4, xgimi.ip, now - 40_000, Action.Disable, "com.google.android.tvrecommendations", "TV Recommendations", Action.Enable, null),
            HistoryEntry(3, xgimi.ip, now - 45_000, Action.Disable, "com.xgimi.assistant", "XGIMI Assistant", Action.Enable, null),
            HistoryEntry(2, xgimi.ip, now - 5 * 60_000, Action.Stop, "com.netflix.ninja", "Netflix", Action.Open, null),
            HistoryEntry(1, xgimi.ip, now - 2 * 3_600_000, Action.Stop, "com.google.android.youtube.tv", "YouTube", Action.Open, now - 3_500_000),
        )
        shot("22_readme_history") { HistoryScreen(deviceState { copy(history = history) }, {}, {}, {}) }
        compose.onNodeWithText("Restore everything (2)").assertExists()
    }
    @Test fun readme_install() {
        // An update, since the sample TV already has Netflix: the dialog says what changes.
        val netflix = PickedApp(
            listOf(apk("base.apk", 38_000_000), apk("split_config.armeabi_v7a.apk", 7_200_000), apk("split_config.xxhdpi.apk", 1_400_000)),
            ApkInfo("com.netflix.ninja", 11_300, "11.3.0"),
            "Netflix",
        )
        sideload("23_readme_install", SideloadUi(SideloadUi.Step.Ready, netflix, InstalledVersion(11_200, "11.2.0")))
    }

    @Test fun readme_files() {
        val files = listOf(
            TvFile("subtitles.srt", 61_000, now - 40 * 60_000L),
            TvFile("kodi-21.1-armeabi-v7a.apk", 74_000_000, now - 5 * 3_600_000L),
            TvFile("projectivy-backup.plbackup", 48_200, now - 26 * 3_600_000L),
            TvFile("holiday-2025.mp4", 812_000_000, now - 3 * 86_400_000L),
            TvFile("wallpaper-mountains.jpg", 2_400_000, now - 9 * 86_400_000L),
        )
        shot("24_readme_files") { DeviceTabsScreen(deviceState { copy(files = FilesUi(TvFolder.DOWNLOAD, files)) }, DeviceActions(), startTab = TvTab.Files) }
        compose.onNodeWithText("wallpaper-mountains.jpg").assertExists()
    }

    // The Tweaks tab opens on the Home screen card: Google TV's own launcher is Home, nothing added yet.
    @Test fun readme_tweaks() {
        val tweaks = TvTweaks(dnsHost = TvSettings.ADGUARD, animationScale = null)
        shot("29_readme_tweaks") { DeviceTabsScreen(deviceState { copy(tweaks = tweaks, apps = listOf(stockHome, playStore) + apps) }, DeviceActions(), startTab = TvTab.Tweaks) }
        compose.onNodeWithText(Copy.GET_LAUNCHER_TEXT).assertExists()
    }

    private val stockHome = AppInfo(
        "com.google.android.tvlauncher", "Google TV Home", 0xFF1A73E8, true, true, "this is your home screen", "1.0.4",
        home = true, answersHome = true, builtInLauncher = true,
    )
    private val playStore = AppInfo("com.android.vending", "Google Play Store", 0xFF01875F, true, true, null, "40.1")
    private val flauncher = AppInfo("me.efesser.flauncher", "FLauncher", 0xFF3F6FD8, false, true, null, "0.18", removable = true, answersHome = true)

    /** Projectivy, installed by the user, as a launcher: [home] it's on Home, the stock launcher off; else it offers to replace it. */
    private fun projectivy(a: AppInfo, home: Boolean) = a.copy(
        protectedReason = "this is your home screen".takeIf { home }, system = false, removable = true, answersHome = true, homeService = true,
        home = home, homeInstead = stockHome.packageName.takeUnless { home }, homeBack = stockHome.label.takeIf { home },
    )

    private fun homeState(home: Boolean, pending: Pair<String, Action>? = null) = deviceState {
        val stock = if (home) stockHome.copy(enabled = false, home = false, answersHome = false, protectedReason = null) else stockHome
        val f = flauncher.copy(homeInstead = if (home) "com.spocky.projengmenu" else stock.packageName)
        copy(apps = listOf(stock, f) + apps.map { if (it.packageName == "com.spocky.projengmenu") projectivy(it, home) else it }, pending = pending)
    }

    // Make Home on Projectivy: the confirm says the TV's own launcher is the only one turned off.
    @Test fun readme_home() {
        shot("30_readme_home", screen = true) { DeviceTabsScreen(homeState(home = false, pending = "com.spocky.projengmenu" to Action.Home), DeviceActions(), startTab = TvTab.Tweaks) }
        compose.onNodeWithText(Copy.makeHomeTitle("Projectivy Launcher")).assertExists()
    }

    private fun homeCard(dark: Boolean) {
        shot("78_tweaks_home_card", dark) { DeviceTabsScreen(homeState(home = true), DeviceActions(), startTab = TvTab.Tweaks) }
        compose.onNodeWithText(Copy.builtIn(off = true)).assertExists()
    }
    @Test fun tweaks_homeCard() = homeCard(false)
    @Test fun tweaks_homeCard_dark() = homeCard(true)

    // No default: Android would ask which home screen to use, so the card says to pick one.
    @Test fun tweaks_homeCardNoDefault() {
        val state = homeState(home = false).let { s -> s.copy(device = s.device.copy(apps = s.device.apps.map { if (it.packageName == stockHome.packageName) it.copy(home = false) else it })) }
        shot("78_tweaks_home_card_no_default") { DeviceTabsScreen(state, DeviceActions(), startTab = TvTab.Tweaks) }
        compose.onNodeWithText(Copy.NO_DEFAULT).assertExists()
    }

    // Back to Google TV Home from Projectivy: turned back on, and Projectivy's service goes off with Home.
    private fun homeBack(dark: Boolean) {
        shot("78_tweaks_home_back", dark, screen = true) {
            DeviceTabsScreen(homeState(home = true, pending = stockHome.packageName to Action.HomeBack), DeviceActions(), startTab = TvTab.Tweaks)
        }
        compose.onNodeWithText(Copy.explainLeave("Google TV Home", off = true, "Projectivy Launcher", currentService = true, service = false)).assertExists()
    }
    @Test fun tweaks_homeBack() = homeBack(false)
    @Test fun tweaks_homeBack_dark() = homeBack(true)

    // On Apps, a launcher's row links to the card rather than switching itself.
    @Test fun apps_launcherRowLinksToTheCard() {
        compose.mainClock.autoAdvance = false
        compose.setContent { TellyscopeTheme(dynamic = false) { DeviceTabsScreen(homeState(home = true), DeviceActions(), startTab = TvTab.Apps) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Projectivy Launcher"))
        compose.onNodeWithText("Projectivy Launcher").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(Copy.HOME_SETTINGS))
        compose.onNodeWithText(Copy.HOME_SETTINGS).performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // The Tweaks tab, on the card.
        compose.onNodeWithText(Copy.opensOnHome("Projectivy Launcher")).assertExists()
    }

    // A keep from the TV's list only warns; a hard lock still offers nothing.
    // Tall enough that Built-in's protected rows are on screen under the pinned buttons and search field:
    // scrolling by text to a row not yet laid out, with the clock held, runs out of memory.
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    @Test fun apps_listKeepWarnsAndHardLockHolds() {
        val proposed = mutableListOf<Pair<String, Action>>()
        val kept = deviceState { copy(apps = apps.map { if (it.packageName == "com.xgimi.assistant") it.copy(listKeep = true) else it }) }
        compose.mainClock.autoAdvance = false
        compose.setContent { TellyscopeTheme(dynamic = false) { DeviceTabsScreen(kept, DeviceActions(propose = { pkg, a -> proposed += pkg to a }), startTab = TvTab.Apps) } }
        compose.mainClock.advanceTimeBy(1_000)
        val tap = { label: String ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
            compose.mainClock.advanceTimeBy(1_000)
        }
        tap("Built-in"); tap("XGIMI Assistant"); tap("Disable")
        assertEquals(listOf("com.xgimi.assistant" to Action.Disable), proposed)
        tap("Google Play services")
        compose.onAllNodesWithText("Disable").assertCountEquals(1) // XGIMI Assistant's, still open
        assertEquals(1, proposed.size)
    }

    @Test fun apps_listKeepConfirmSaysSo() {
        val state = deviceState {
            copy(apps = apps.map { if (it.packageName == "com.xgimi.assistant") it.copy(listKeep = true) else it }, pending = "com.xgimi.assistant" to Action.Disable)
        }
        shot("79_apps_keep_warning", before = { compose.onNode(hasScrollAction()).performScrollToNode(hasText(Copy.confirm(Action.Disable))); Unit }) {
            DeviceTabsScreen(state, DeviceActions(), startTab = TvTab.Apps)
        }
        compose.onNodeWithText(Copy.KEEP_WARNING, substring = true).assertExists()
    }

    @Test fun readme_send() = shot("25_readme_send", screen = true) {
        val t = TransferUi("family-trip-2025.mp4", toTv = true, folder = TvFolder.MOVIES, progress = Progress(486_000_000, 812_000_000))
        DeviceTabsScreen(deviceState { copy(transfer = t) }, DeviceActions())
    }

    // Connected devices and the access warning (0.9): the cards on their own, then the TV page with
    // both, then Who has access and each step of Remove all other access.
    private fun accessState(removal: Removal? = null) = runBlocking {
        md3State().let { it.copy(device = it.device.copy(access = fake.access(it.selectedId!!), peripherals = fake.peripherals(it.selectedId!!), removal = removal)) }
    }

    private fun card(name: String, dark: Boolean, content: @Composable () -> Unit) = shot(name, dark) {
        Surface { Column(Modifier.padding(16.dp)) { content() } }
    }

    @Test fun access_warning() = card("90_access_warning", false) { AccessWarning(accessState().device.access!!, AccessActions(), onWho = {}) }
    @Test fun access_warning_dark() = card("90_access_warning", true) { AccessWarning(accessState().device.access!!, AccessActions(), onWho = {}) }
    @Test fun access_warning_open() = card("91_access_warning_open", false) { AccessWarning(accessState().device.access!!, AccessActions(), onWho = {}, startOpen = true) }
    @Test fun access_warning_open_dark() = card("91_access_warning_open", true) { AccessWarning(accessState().device.access!!, AccessActions(), onWho = {}, startOpen = true) }
    @Test fun devices_card() = card("92_connected_devices", false) { ConnectedDevicesCard(accessState().device.peripherals!!) }
    @Test fun devices_card_dark() = card("92_connected_devices", true) { ConnectedDevicesCard(accessState().device.peripherals!!) }
    @Test fun devices_card_open() = card("93_connected_devices_open", false) { ConnectedDevicesCard(accessState().device.peripherals!!, startOpen = true) }
    @Test fun devices_card_open_dark() = card("93_connected_devices_open", true) { ConnectedDevicesCard(accessState().device.peripherals!!, startOpen = true) }

    /** The Overview scrolled to the warning: above the stats, then the tiles, the list, devices and Android. */
    private fun accessOverview(dark: Boolean) {
        // By key: scrolling to a node by tag never stops with the clock held.
        val scroll = { compose.onNode(hasScrollAction()).performScrollToKey("accessWarning"); Unit }
        // Built once: a fresh state on every recomposition would never let the page settle.
        val state = accessState()
        shot("94_overview_access", dark, before = scroll) { DeviceTabsScreen(state, DeviceActions()) }
        compose.onNodeWithTag("accessWarning").assertExists()
    }
    @Test fun access_overview() = accessOverview(false)
    @Test fun access_overview_dark() = accessOverview(true)

    private fun accessScreen(name: String, dark: Boolean, removal: Removal?) {
        val state = accessState(removal)
        shot(name, dark, screen = removal?.step == Removal.Step.Confirm) { AccessScreen(state, AccessActions()) }
    }
    @Test fun access_list() = accessScreen("95_who_has_access", false, null)
    @Test fun access_list_dark() = accessScreen("95_who_has_access", true, null)
    @Test fun access_confirm() = accessScreen("96_remove_confirm", false, Removal(Removal.Step.Confirm))
    @Test fun access_confirm_dark() = accessScreen("96_remove_confirm", true, Removal(Removal.Step.Confirm))
    private val allow = Removal(Removal.Step.Allow, fingerprint = "3F:9A:1C:5D:E0:42:B7:19:8C:6A:F3:21:0D:94:7E:C2")
    @Test fun access_allow() = accessScreen("97_remove_allow", false, allow)
    @Test fun access_allow_dark() = accessScreen("97_remove_allow", true, allow)
    @Test fun access_done() = accessScreen("98_remove_done", false, Removal(Removal.Step.Done))
    @Test fun access_done_dark() = accessScreen("98_remove_done", true, Removal(Removal.Step.Done))
    private val still = Removal(Removal.Step.StillConnected, still = Copy.connectionFrom("192.0.2.47"), open = true)
    @Test fun access_still() = accessScreen("99_remove_still", false, still)
    @Test fun access_still_dark() = accessScreen("99_remove_still", true, still)
    @Test fun access_unconfirmed() = accessScreen("99_remove_unconfirmed", false, Removal(Removal.Step.Unconfirmed))
    @Test fun access_unconfirmed_dark() = accessScreen("99_remove_unconfirmed", true, Removal(Removal.Step.Unconfirmed))
    @Test fun access_manual() = accessScreen("99_remove_manual", false, Removal(Removal.Step.Manual))
    @Test fun access_manual_dark() = accessScreen("99_remove_manual", true, Removal(Removal.Step.Manual))
}
