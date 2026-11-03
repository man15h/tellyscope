// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.tellyscope.R
import app.tellyscope.adb.ApkInfo
import app.tellyscope.adb.InstallResult
import app.tellyscope.adb.Progress
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FIRE_TV_LAUNCHER
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.KnownLauncher
import app.tellyscope.data.LauncherDownload
import app.tellyscope.data.LauncherRelease
import app.tellyscope.data.PickedApp
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.data.Suggestions
import app.tellyscope.data.UiText
import app.tellyscope.data.WrongApk
import app.tellyscope.data.isFireOs
import app.tellyscope.data.apkName
import app.tellyscope.data.latestTag
import app.tellyscope.data.pageApk
import app.tellyscope.data.parseLaunchers
import app.tellyscope.data.parseRelease
import app.tellyscope.ui.FireInstall
import app.tellyscope.ui.LauncherFlow
import app.tellyscope.ui.PLAY_STORE
import app.tellyscope.ui.RECOMMENDED
import app.tellyscope.ui.TellyscopeViewModel
import app.tellyscope.ui.fireInstall
import app.tellyscope.ui.fireLauncher
import app.tellyscope.ui.groupedHex
import app.tellyscope.ui.offerChoose
import app.tellyscope.ui.offerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

// Fixtures from Amazon's developer docs, not from a TV: Fire TV devices report Build.MANUFACTURER "Amazon" and an
// AFT model (AFTKRT is the Fire TV Stick 4K Max, 2nd gen); Fire OS's home screen is com.amazon.tv.launcher, also on
// Fire TV Edition sets made by others.
private fun tv(manufacturer: String?, model: String?) =
    Device("tv", ScanHit("tv", 5555, ScanKind.Classic, null, null), DeviceState.Online, model, manufacturer, "11", 0L)

private val amazon = tv("Amazon", "AFTKRT")
private val insignia = tv("Insignia", "NS-43F301NA22")
private val androidTv = tv("Google", "Chromecast")

private val fireHome = AppInfo(FIRE_TV_LAUNCHER, "Fire TV Home", null, true, true, UiText(R.string.data_protected_home_screen), "1.0", home = true)
private val store = AppInfo(PLAY_STORE, "Google Play Store", null, true, true, null, "1.0")
private val tvHome = AppInfo("com.google.android.tvlauncher", "Google TV Home", null, true, true, UiText(R.string.data_protected_home_screen), "1.0", home = true)

// Sample data from the test launcher list (fixtures/launchers.json): its Fire OS launcher.
private const val PROJECTIVY = "com.spocky.projengmenu"
private const val SIGNER = "f6697bf4082ee97511e4de07863193884a015b7ab5860430321bda1042b0aadd"
private const val RELEASES = "https://github.com/spocky/miproja1/releases"
private val projectivy get() = RECOMMENDED.first { it.pkg == PROJECTIVY }.launcher

/** A list the user added in place of the default source. */
private const val UNVERIFIED = "https://raw.githubusercontent.com/example/tellyscope/main/data/"

private const val TAG = "4.72"
private const val PAGE = "https://github.com/spocky/miproja1/releases/tag/$TAG"
private const val APK = "https://github.com/spocky/miproja1/releases/download/$TAG/ProjectivyLauncher-4.72-c97-xda-release.apk"

/** GitHub's releases/latest, cut to the fields read. */
private fun release(
    tag: String = TAG,
    page: String = PAGE,
    assets: List<Pair<String, String>> = listOf("ProjectivyLauncher-4.72-c97-xda-release.apk" to APK),
    prerelease: Boolean = false,
    size: Long = 11432750,
) = """{"tag_name": "$tag", "html_url": "$page", "draft": false, "prerelease": $prerelease, "assets": [${
    assets.joinToString { (name, url) -> """{"name": "$name", "browser_download_url": "$url", "size": $size}""" }
}]}"""

/** A Fire TV with no Play Store, which takes whatever Tellyscope installs; or [tv] with [own] on it. */
private class FireTv(val tv: Device = amazon, val own: List<AppInfo> = listOf(fireHome)) : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    val installs = mutableListOf<ApkInfo>()
    var refuse: InstallResult? = null

    override suspend fun known() = listOf(tv)
    override fun live(deviceId: String): Flow<Snapshot> = emptyFlow()
    override suspend fun suggestions(deviceId: String, apps: suspend () -> List<AppInfo>) = Suggestions.Unavailable
    override suspend fun history(deviceId: String) = emptyList<HistoryEntry>()
    override suspend fun apps(deviceId: String) = own +
        if (installs.isEmpty()) emptyList() else listOf(AppInfo(PROJECTIVY, "Projectivy Launcher", null, false, true, null, "1.0", homeInstead = FIRE_TV_LAUNCHER, homeService = true))

    override suspend fun install(deviceId: String, apks: List<File>, apk: ApkInfo, allowDowngrade: Boolean, onProgress: (Progress) -> Unit): InstallResult {
        refuse?.let { return it }
        installs += apk
        return InstallResult.Done
    }
}

private class FakeDownload(var signedByDeveloper: Boolean = true) : LauncherDownload {
    var fetched = 0
    var offline = false
    override suspend fun latest(launcher: KnownLauncher) = if (offline) throw IOException("offline") else parseRelease(release(), launcher)!!
    override suspend fun fetch(launcher: KnownLauncher, release: LauncherRelease, onProgress: (Progress) -> Unit): PickedApp {
        fetched++
        if (!signedByDeveloper) throw WrongApk()
        return PickedApp(listOf(File("launcher.apk")), ApkInfo(launcher.pkg, 97, release.version), launcher.name)
    }
    override suspend fun open(uri: Uri, launcher: KnownLauncher): PickedApp {
        if (!signedByDeveloper) throw WrongApk()
        return PickedApp(listOf(File("launcher.apk")), ApkInfo(launcher.pkg, 97, "4.72"), launcher.name)
    }
}

/** Install a home screen on Fire OS: who gets it, the release and signature checks, and the install. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FireOsTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private var source = FireTv()
    private val download = FakeDownload()
    private var vm: TellyscopeViewModel? = null
    /** The launcher list's address while it fails its check, as the app reads it now: a test changes it mid-flow. */
    private var unverified: String? = null

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    @Test fun anAmazonDeviceIsFireOs() {
        assertTrue(isFireOs(amazon, emptyList()))
        assertTrue(fireInstall(amazon, listOf(fireHome)))
    }

    @Test fun anotherMakersTvWithAmazonsLauncherIsFireOs() {
        assertTrue(isFireOs(insignia, listOf(fireHome)))
        assertFalse(isFireOs(insignia, emptyList()))
    }

    @Test fun plainAndroidTvKeepsThePlayStore() {
        assertFalse(isFireOs(androidTv, listOf(store, tvHome)))
        assertFalse(fireInstall(androidTv, listOf(store, tvHome)))
    }

    @Test fun fireOsGetsTheCardWithoutAPlayStore() {
        val fire = fireInstall(amazon, listOf(fireHome))
        assertTrue(offerChoose(listOf(fireHome), fire))
        assertTrue(offerLauncher(listOf(fireHome), hidden = false, fire = fire))
        assertFalse(offerLauncher(listOf(fireHome), hidden = false))
    }

    @Test fun theListsFireOsLauncherIsTheOneFireOsInstalls() {
        assertEquals(PROJECTIVY, fireLauncher()?.pkg)
        assertEquals(setOf(SIGNER), projectivy.signers)
        assertEquals(64L shl 20, projectivy.maxBytes)
        assertEquals(RELEASES, projectivy.releases)
    }

    @Test fun aFireOsLauncherWithoutSignersIsntOffered() {
        val unsigned = RECOMMENDED.map { if (it.pkg == PROJECTIVY) it.copy(launcher = it.launcher.copy(signers = emptySet())) else it }
        assertNull(fireLauncher(unsigned))
        assertFalse(fireInstall(amazon, listOf(fireHome), unsigned))
    }

    @Test fun installFieldsThatDontReadAreDropped() {
        fun one(fields: String) = parseLaunchers("""{"schema": 1, "launchers": [{"package": "a.b", "name": "A", $fields}]}""").single()
        assertEquals(emptySet<String>(), one(""""signers": ["$SIGNER", "F6697BF4"]""").signers)
        assertEquals(256L shl 20, one(""""max_mb": 100000""").maxBytes)
        assertNull(one(""""releases": "https://example.com/a/b/releases"""").releases)
        assertNull(one(""""releases": "https://github.com/x/../releases"""").releases)
        assertNull(one(""""releases": "https://github.com/x/./releases"""").releases)
        assertEquals("https://github.com/x/.github/releases", one(""""releases": "https://github.com/x/.github/releases"""").releases)
    }

    @Test fun theSignerReadsInGroupsOfEight() {
        assertEquals("f6697bf4 082ee975 11e4de07 86319388\n4a015b7a b5860430 321bda10 42b0aadd", groupedHex(SIGNER))
    }

    @Test fun aListWithoutItsFireOsLauncherLeavesFireOsAsToday() {
        val without = RECOMMENDED.filter { it.pkg != PROJECTIVY }
        assertTrue(without.isNotEmpty())
        val fire = fireInstall(amazon, listOf(fireHome), without)
        assertFalse(fire)
        assertFalse(offerChoose(listOf(fireHome), fire))
        assertFalse(offerLauncher(listOf(fireHome), hidden = false, fire = fire))
    }

    @Test fun theLatestReleaseFromTheLaunchersRepo() {
        assertEquals(LauncherRelease(TAG, PAGE, APK, 11432750), parseRelease(release(), projectivy))
    }

    @Test fun releasesThatArentTheLaunchersOwnAreRefused() {
        assertNull(parseRelease(release(prerelease = true), projectivy))
        assertNull(parseRelease(release(page = "https://github.com/someone/miproja1/releases/tag/$TAG"), projectivy))
        assertNull(parseRelease(release(assets = listOf("a.apk" to APK, "b.apk" to APK)), projectivy))
        assertNull(parseRelease(release(assets = listOf("p.apk" to "https://example.com/$TAG/p.apk")), projectivy))
        assertNull(parseRelease(release(assets = listOf("p.apk" to APK.replace("/$TAG/", "/4.70/"))), projectivy))
        assertNull(parseRelease(release(size = 0), projectivy))
        assertNull(parseRelease(release(size = 200L shl 20), projectivy))
        assertNull(parseRelease(release(), projectivy.copy(releases = null)))
        assertNull(parseRelease("not json", projectivy))
    }

    @Test fun onlyTheLauncherSignedByItsDeveloper() {
        assertTrue(projectivy.accepts(PROJECTIVY, setOf(SIGNER)))
        assertFalse(projectivy.accepts(PROJECTIVY, setOf("0".repeat(64))))
        assertFalse(projectivy.accepts(PROJECTIVY, setOf(SIGNER, "0".repeat(64))))
        assertFalse(projectivy.accepts(PROJECTIVY, emptySet()))
        assertFalse(projectivy.accepts("com.example.launcher", setOf(SIGNER)))
        assertFalse(projectivy.copy(signers = emptySet()).accepts(PROJECTIVY, setOf(SIGNER)))
    }

    /** [list]: the launcher list's address, as one that failed its check. [play]: the Play build. */
    private fun TestScope.opened(list: String? = null, play: Boolean = false): TellyscopeViewModel {
        unverified = list
        val v = TellyscopeViewModel(source, clock = { 0L }, launcherDownload = download, unverified = { unverified }, play = play)
        vm = v
        runCurrent()
        v.openLauncherFlow()
        return v
    }

    @Test fun itDownloadsChecksAndInstallsThenOffersTheSwitch() = with(test) {
        val v = opened()
        val flow = v.state.value.device.launcher!!
        assertEquals(PROJECTIVY, flow.pkg)
        assertEquals(FireInstall(), flow.fire)
        assertNull(flow.fire?.list)

        v.checkLauncherApk(PROJECTIVY)
        runCurrent()
        assertEquals(FireInstall.Step.Confirm, v.state.value.device.launcher?.fire?.step)
        assertEquals(PAGE, v.state.value.device.launcher?.fire?.release?.page)

        v.installLauncherApk()
        runCurrent()
        assertEquals(listOf(PROJECTIVY), source.installs.map { it.packageName })
        assertEquals(LauncherFlow.Step.Installed, v.state.value.device.launcher?.step)
        // The install dialog closed with it: left on Installing, it covered the switch and nothing could close it.
        assertNull(v.state.value.device.launcher?.fire?.step)
    }

    @Test fun aWrongSignatureInstallsNothing() = with(test) {
        download.signedByDeveloper = false
        val v = opened()
        v.checkLauncherApk(PROJECTIVY)
        runCurrent()
        v.installLauncherApk()
        runCurrent()
        assertEquals(1, download.fetched)
        assertTrue(source.installs.isEmpty())
        assertEquals(FireInstall.Step.WrongApk, v.state.value.device.launcher?.fire?.step)
        assertEquals(LauncherFlow.Step.Pick, v.state.value.device.launcher?.step)
    }

    @Test fun withThePlayStoreDownloadDirectlyWarnsFirstThenInstalls() = with(test) {
        source = FireTv(androidTv, listOf(store, tvHome))
        val v = opened()
        assertNull(v.state.value.device.launcher?.fire)
        assertTrue(v.directDownload)

        v.checkLauncherApk(PROJECTIVY)
        runCurrent()
        val warning = v.state.value.device.launcher?.fire
        assertEquals(FireInstall.Step.Confirm, warning?.step)
        assertEquals(true, warning?.store)
        assertTrue(source.installs.isEmpty())

        v.installLauncherApk()
        runCurrent()
        assertEquals(listOf(PROJECTIVY), source.installs.map { it.packageName })
        assertEquals(LauncherFlow.Step.Installed, v.state.value.device.launcher?.step)
        assertNull(v.state.value.device.launcher?.fire)
    }

    @Test fun cancellingTheWarningGoesBackToThePicker() = with(test) {
        source = FireTv(androidTv, listOf(store, tvHome))
        val v = opened()
        v.checkLauncherApk(PROJECTIVY)
        runCurrent()
        v.dismissLauncherApk()
        assertEquals(LauncherFlow.Step.Pick, v.state.value.device.launcher?.step)
        assertNull(v.state.value.device.launcher?.fire)
        assertEquals(0, download.fetched)
    }

    @Test fun theApkIsSavedUnderItsCleanedName() {
        assertEquals("Projectivy-Launcher.apk", apkName(projectivy.copy(name = "Projectivy Launcher")))
        assertEquals("....Evil-Home.apk", apkName(projectivy.copy(name = "../../Evil Home")))
        assertEquals("$PROJECTIVY.apk", apkName(projectivy.copy(name = "//?")))
        assertEquals("a".repeat(64) + ".apk", apkName(projectivy.copy(name = "a".repeat(80))))
    }

    @Test fun whenTheApiIsLimitedTheReleasePageGivesTheTagAndItsOneApk() {
        assertEquals(TAG, latestTag(PAGE, RELEASES))
        assertNull(latestTag("https://github.com/someone/miproja1/releases/tag/$TAG", RELEASES))
        assertNull(latestTag(null, RELEASES))
        val link = APK.removePrefix("https://github.com")
        assertEquals(APK, pageApk("""<a href="$link" rel="nofollow">x</a><a href="$link">x</a>""", TAG, RELEASES))
        assertNull(pageApk("""<a href="$link">a</a><a href="${link.replace(".apk", "-b.apk")}">b</a>""", TAG, RELEASES))
        assertNull(pageApk("""<a href="${link.replace("/$TAG/", "/4.70/")}">old</a>""", TAG, RELEASES))
    }

    @Test fun aPickedFileIsCheckedThenInstalled() = with(test) {
        val v = couldntDownload()
        v.pickedLauncherApk(Uri.parse("content://downloads/1"))
        runCurrent()
        assertEquals(listOf(PROJECTIVY), source.installs.map { it.packageName })
        assertEquals(LauncherFlow.Step.Installed, v.state.value.device.launcher?.step)
    }

    @Test fun aPickedFileThatIsntTheLauncherInstallsNothing() = with(test) {
        download.signedByDeveloper = false
        val v = couldntDownload()
        v.pickedLauncherApk(Uri.parse("content://downloads/1"))
        runCurrent()
        assertTrue(source.installs.isEmpty())
        val fire = v.state.value.device.launcher?.fire
        assertEquals(FireInstall.Step.WrongApk, fire?.step)
        assertEquals(true, fire?.picked)
    }

    @Test fun aPickOutsideAFailedStepIsIgnored() = with(test) {
        val v = opened()
        v.pickedLauncherApk(Uri.parse("content://downloads/1"))
        runCurrent()
        assertTrue(source.installs.isEmpty())
        assertNull(v.state.value.device.launcher?.fire?.step)
    }

    @Test fun fromAnUnverifiedListNothingDownloadsUntilTheyTrustIt() = with(test) {
        val v = opened(list = UNVERIFIED)
        assertEquals(UNVERIFIED, v.state.value.device.launcher?.fire?.list)
        v.checkLauncherApk(PROJECTIVY)
        runCurrent()
        val confirm = v.state.value.device.launcher?.fire
        assertEquals(FireInstall.Step.Confirm, confirm?.step)
        assertEquals(UNVERIFIED, confirm?.list)
        assertTrue(confirm!!.gated)

        v.installLauncherApk()
        runCurrent()
        assertEquals(0, download.fetched)
        assertEquals(FireInstall.Step.Confirm, v.state.value.device.launcher?.fire?.step)

        v.trustLauncherList(true)
        v.installLauncherApk()
        runCurrent()
        assertEquals(listOf(PROJECTIVY), source.installs.map { it.packageName })
    }

    @Test fun fromAnUnverifiedListDownloadDirectlyWaitsForTheTickToo() = with(test) {
        source = FireTv(androidTv, listOf(store, tvHome))
        val v = opened(list = UNVERIFIED)
        v.checkLauncherApk(PROJECTIVY)
        runCurrent()
        assertEquals(true, v.state.value.device.launcher?.fire?.gated)
        v.installLauncherApk()
        runCurrent()
        assertEquals(0, download.fetched)
        v.trustLauncherList(true)
        v.installLauncherApk()
        runCurrent()
        assertEquals(1, download.fetched)
    }

    @Test fun fromAnUnverifiedListThePickWaitsForTheTick() = with(test) {
        val v = couldntDownload(list = UNVERIFIED)
        v.pickedLauncherApk(Uri.parse("content://downloads/1"))
        runCurrent()
        assertTrue(source.installs.isEmpty())
        assertEquals(FireInstall.Step.NoDownload, v.state.value.device.launcher?.fire?.step)

        v.trustLauncherList(true)
        v.pickedLauncherApk(Uri.parse("content://downloads/1"))
        runCurrent()
        assertEquals(listOf(PROJECTIVY), source.installs.map { it.packageName })
    }

    @Test fun dismissingAWrongPickAsksForTheTickAgain() = with(test) {
        val v = couldntDownload(list = UNVERIFIED)
        v.trustLauncherList(true)
        download.signedByDeveloper = false
        v.pickedLauncherApk(Uri.parse("content://downloads/1"))
        runCurrent()
        assertEquals(FireInstall.Step.WrongApk, v.state.value.device.launcher?.fire?.step)

        v.dismissLauncherApk()
        val fire = v.state.value.device.launcher?.fire
        assertEquals(UNVERIFIED, fire?.list)
        assertEquals(true, fire?.gated)
    }

    @Test fun thePlayBuildPicksAFileFromTheStartAndDownloadsNothing() = with(test) {
        val v = opened(play = true)
        assertNull(v.state.value.device.launcher?.fire?.step)
        v.pickedLauncherApk(Uri.parse("content://downloads/1"))
        runCurrent()
        assertEquals(0, download.fetched)
        assertEquals(listOf(PROJECTIVY), source.installs.map { it.packageName })
        assertEquals(LauncherFlow.Step.Installed, v.state.value.device.launcher?.step)
    }

    @Test fun thePlayBuildOffersNoDownloadDirectly() = with(test) {
        source = FireTv(androidTv, listOf(store, tvHome))
        assertFalse(opened(play = true).directDownload)
    }

    @Test fun inThePlayBuildFromAnUnverifiedListThePickWaitsForTheTick() = with(test) {
        val v = opened(list = UNVERIFIED, play = true)
        assertEquals(true, v.state.value.device.launcher?.fire?.gated)
        v.pickedLauncherApk(Uri.parse("content://downloads/1"))
        runCurrent()
        assertTrue(source.installs.isEmpty())

        v.trustLauncherList(true)
        v.pickedLauncherApk(Uri.parse("content://downloads/1"))
        runCurrent()
        assertEquals(0, download.fetched)
        assertEquals(listOf(PROJECTIVY), source.installs.map { it.packageName })
    }

    @Test fun failingTheCheckUnderConfirmChecksAgainFirst() = with(test) {
        val v = opened()
        v.checkLauncherApk(PROJECTIVY)
        runCurrent()
        assertEquals(false, v.state.value.device.launcher?.fire?.gated)

        unverified = UNVERIFIED
        v.installLauncherApk()
        runCurrent()
        assertEquals(0, download.fetched)
        val fire = v.state.value.device.launcher?.fire
        assertEquals(FireInstall.Step.Confirm, fire?.step)
        assertEquals(UNVERIFIED, fire?.list)
        assertEquals(true, fire?.gated)
    }

    @Test fun failingTheCheckAfterCouldntDownloadGatesThePick() = with(test) {
        val v = couldntDownload()
        unverified = UNVERIFIED
        v.pickedLauncherApk(Uri.parse("content://downloads/1"))
        runCurrent()
        assertTrue(source.installs.isEmpty())
        assertEquals(true, v.state.value.device.launcher?.fire?.gated)
    }

    @Test fun onTheDefaultSourceTheTickDoesNothing() = with(test) {
        val v = opened()
        v.trustLauncherList(true)
        assertEquals(FireInstall(), v.state.value.device.launcher?.fire)
    }

    private fun TestScope.couldntDownload(list: String? = null): TellyscopeViewModel {
        download.offline = true
        val v = opened(list)
        v.checkLauncherApk(PROJECTIVY)
        runCurrent()
        assertEquals(FireInstall.Step.NoDownload, v.state.value.device.launcher?.fire?.step)
        return v
    }
}
