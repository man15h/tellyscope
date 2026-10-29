// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.net.Uri
import androidx.lifecycle.viewModelScope
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
import app.tellyscope.data.LauncherDownload
import app.tellyscope.data.LauncherRelease
import app.tellyscope.data.NotProjectivy
import app.tellyscope.data.PROJECTIVY
import app.tellyscope.data.PROJECTIVY_SIGNERS
import app.tellyscope.data.PickedApp
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.data.Suggestions
import app.tellyscope.data.isFireOs
import app.tellyscope.data.isProjectivy
import app.tellyscope.data.latestTag
import app.tellyscope.data.pageApk
import app.tellyscope.data.parseRelease
import app.tellyscope.ui.FireInstall
import app.tellyscope.ui.LauncherFlow
import app.tellyscope.ui.PLAY_STORE
import app.tellyscope.ui.RECOMMENDED
import app.tellyscope.ui.TellyscopeViewModel
import app.tellyscope.ui.fireInstall
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

private val fireHome = AppInfo(FIRE_TV_LAUNCHER, "Fire TV Home", null, true, true, "this is your home screen", "1.0", home = true)
private val store = AppInfo(PLAY_STORE, "Google Play Store", null, true, true, null, "1.0")
private val tvHome = AppInfo("com.google.android.tvlauncher", "Google TV Home", null, true, true, "this is your home screen", "1.0", home = true)

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
    override suspend fun latest() = if (offline) throw IOException("offline") else parseRelease(release())!!
    override suspend fun fetch(release: LauncherRelease, onProgress: (Progress) -> Unit): PickedApp {
        fetched++
        if (!signedByDeveloper) throw NotProjectivy()
        return PickedApp(listOf(File("projectivy.apk")), ApkInfo(PROJECTIVY, 97, release.version), "Projectivy Launcher")
    }
    override suspend fun open(uri: Uri): PickedApp {
        if (!signedByDeveloper) throw NotProjectivy()
        return PickedApp(listOf(File("projectivy.apk")), ApkInfo(PROJECTIVY, 97, "4.72"), "Projectivy Launcher")
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

    @Test fun aListWithoutProjectivyLeavesFireOsAsToday() {
        val without = RECOMMENDED.filter { it.pkg != PROJECTIVY }
        assertTrue(without.isNotEmpty())
        val fire = fireInstall(amazon, listOf(fireHome), without)
        assertFalse(fire)
        assertFalse(offerChoose(listOf(fireHome), fire))
        assertFalse(offerLauncher(listOf(fireHome), hidden = false, fire = fire))
    }

    @Test fun theLatestReleaseFromProjectivysRepo() {
        assertEquals(LauncherRelease(TAG, PAGE, APK, 11432750), parseRelease(release()))
    }

    @Test fun releasesThatArentProjectivysOwnAreRefused() {
        assertNull(parseRelease(release(prerelease = true)))
        assertNull(parseRelease(release(page = "https://github.com/someone/miproja1/releases/tag/$TAG")))
        assertNull(parseRelease(release(assets = listOf("a.apk" to APK, "b.apk" to APK))))
        assertNull(parseRelease(release(assets = listOf("p.apk" to "https://example.com/$TAG/p.apk"))))
        assertNull(parseRelease(release(assets = listOf("p.apk" to APK.replace("/$TAG/", "/4.70/")))))
        assertNull(parseRelease(release(size = 0)))
        assertNull(parseRelease(release(size = 200L shl 20)))
        assertNull(parseRelease("not json"))
    }

    @Test fun onlyProjectivySignedByItsDeveloper() {
        assertTrue(isProjectivy(PROJECTIVY, PROJECTIVY_SIGNERS))
        assertFalse(isProjectivy(PROJECTIVY, setOf("0".repeat(64))))
        assertFalse(isProjectivy(PROJECTIVY, PROJECTIVY_SIGNERS + "0".repeat(64)))
        assertFalse(isProjectivy(PROJECTIVY, emptySet()))
        assertFalse(isProjectivy("com.example.launcher", PROJECTIVY_SIGNERS))
    }

    private fun TestScope.opened(): TellyscopeViewModel {
        val v = TellyscopeViewModel(source, clock = { 0L }, launcherDownload = download)
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

        v.checkProjectivy()
        runCurrent()
        assertEquals(FireInstall.Step.Confirm, v.state.value.device.launcher?.fire?.step)
        assertEquals(PAGE, v.state.value.device.launcher?.fire?.release?.page)

        v.installProjectivy()
        runCurrent()
        assertEquals(listOf(PROJECTIVY), source.installs.map { it.packageName })
        assertEquals(LauncherFlow.Step.Installed, v.state.value.device.launcher?.step)
        // The install dialog closed with it: left on Installing, it covered the switch and nothing could close it.
        assertNull(v.state.value.device.launcher?.fire?.step)
    }

    @Test fun aWrongSignatureInstallsNothing() = with(test) {
        download.signedByDeveloper = false
        val v = opened()
        v.checkProjectivy()
        runCurrent()
        v.installProjectivy()
        runCurrent()
        assertEquals(1, download.fetched)
        assertTrue(source.installs.isEmpty())
        assertEquals(FireInstall.Step.NotProjectivy, v.state.value.device.launcher?.fire?.step)
        assertEquals(LauncherFlow.Step.Pick, v.state.value.device.launcher?.step)
    }

    @Test fun withThePlayStoreDownloadDirectlyWarnsFirstThenInstalls() = with(test) {
        source = FireTv(androidTv, listOf(store, tvHome))
        val v = opened()
        assertNull(v.state.value.device.launcher?.fire)
        assertTrue(v.directDownload)

        v.checkProjectivy()
        runCurrent()
        val warning = v.state.value.device.launcher?.fire
        assertEquals(FireInstall.Step.Confirm, warning?.step)
        assertEquals(true, warning?.store)
        assertTrue(source.installs.isEmpty())

        v.installProjectivy()
        runCurrent()
        assertEquals(listOf(PROJECTIVY), source.installs.map { it.packageName })
        assertEquals(LauncherFlow.Step.Installed, v.state.value.device.launcher?.step)
        assertNull(v.state.value.device.launcher?.fire)
    }

    @Test fun cancellingTheWarningGoesBackToThePicker() = with(test) {
        source = FireTv(androidTv, listOf(store, tvHome))
        val v = opened()
        v.checkProjectivy()
        runCurrent()
        v.dismissProjectivy()
        assertEquals(LauncherFlow.Step.Pick, v.state.value.device.launcher?.step)
        assertNull(v.state.value.device.launcher?.fire)
        assertEquals(0, download.fetched)
    }

    @Test fun whenTheApiIsLimitedTheReleasePageGivesTheTagAndItsOneApk() {
        assertEquals(TAG, latestTag(PAGE))
        assertNull(latestTag("https://github.com/someone/miproja1/releases/tag/$TAG"))
        assertNull(latestTag(null))
        val link = APK.removePrefix("https://github.com")
        assertEquals(APK, pageApk("""<a href="$link" rel="nofollow">x</a><a href="$link">x</a>""", TAG))
        assertNull(pageApk("""<a href="$link">a</a><a href="${link.replace(".apk", "-b.apk")}">b</a>""", TAG))
        assertNull(pageApk("""<a href="${link.replace("/$TAG/", "/4.70/")}">old</a>""", TAG))
    }

    @Test fun aPickedFileIsCheckedThenInstalled() = with(test) {
        val v = couldntDownload()
        v.pickedProjectivy(Uri.parse("content://downloads/1"))
        runCurrent()
        assertEquals(listOf(PROJECTIVY), source.installs.map { it.packageName })
        assertEquals(LauncherFlow.Step.Installed, v.state.value.device.launcher?.step)
    }

    @Test fun aPickedFileThatIsntProjectivyInstallsNothing() = with(test) {
        download.signedByDeveloper = false
        val v = couldntDownload()
        v.pickedProjectivy(Uri.parse("content://downloads/1"))
        runCurrent()
        assertTrue(source.installs.isEmpty())
        val fire = v.state.value.device.launcher?.fire
        assertEquals(FireInstall.Step.NotProjectivy, fire?.step)
        assertEquals(true, fire?.picked)
    }

    @Test fun aPickOutsideAFailedStepIsIgnored() = with(test) {
        val v = opened()
        v.pickedProjectivy(Uri.parse("content://downloads/1"))
        runCurrent()
        assertTrue(source.installs.isEmpty())
        assertNull(v.state.value.device.launcher?.fire?.step)
    }

    private fun TestScope.couldntDownload(): TellyscopeViewModel {
        download.offline = true
        val v = opened()
        v.checkProjectivy()
        runCurrent()
        assertEquals(FireInstall.Step.NoDownload, v.state.value.device.launcher?.fire?.step)
        return v
    }
}
