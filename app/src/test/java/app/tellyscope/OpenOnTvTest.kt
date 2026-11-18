// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.tellyscope.adb.ApkInfo
import app.tellyscope.adb.InstallResult
import app.tellyscope.adb.Progress
import app.tellyscope.adb.TvFile
import app.tellyscope.adb.TvFolder
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.KnownLauncher
import app.tellyscope.data.LauncherPick
import app.tellyscope.data.MediaKind
import app.tellyscope.data.PLAYER
import app.tellyscope.data.PickedApp
import app.tellyscope.data.PlayerDownload
import app.tellyscope.data.PlayerRelease
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.data.StoreState
import app.tellyscope.data.Suggestions
import app.tellyscope.data.WrongApk
import app.tellyscope.data.handlers
import app.tellyscope.data.media
import app.tellyscope.data.parseSha256
import app.tellyscope.data.parseVlcListing
import app.tellyscope.ui.AMAZON_APPSTORE
import app.tellyscope.ui.Copy
import app.tellyscope.ui.PLAY_STORE
import app.tellyscope.ui.PlayerFlow
import app.tellyscope.ui.PlayerStore
import app.tellyscope.ui.STORE_POLL_MS
import app.tellyscope.ui.TellyscopeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

private const val GALLERY = "com.android.gallery3d"
private const val STUBS = "com.google.android.tv.frameworkpackagestubs"
private const val KODI = "org.xbmc.kodi"

private val holiday = TvFile("holiday 2025.mp4", 774_400_000, 0L)
private val podcast = TvFile("podcast-ep12.mp3", 48_300_000, 0L)
private val photo = TvFile("beach.jpg", 2_000_000, 0L)

private val playStore = AppInfo(PLAY_STORE, "Google Play Store", null, true, true, null, "1.0")
private val appstore = AppInfo(AMAZON_APPSTORE, "Appstore", null, true, true, null, "1.0")
private val gallery = AppInfo(GALLERY, "Gallery", null, true, true, null, "1.0")

// Amazon's AFT models are Fire TVs, as in FireOsTest.
private fun tv(manufacturer: String, model: String) = Device("tv", ScanHit("tv", 5555, ScanKind.Classic, null, null), DeviceState.Online, model, manufacturer, "11", 0L)

/** A TV whose apps answer for a file as [answer] says; it records what it was asked to open and install. */
private class PlayerTv(val device: Device = tv("XGIMI", "Horizon Pro"), val own: List<AppInfo> = listOf(playStore, gallery)) :
    DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    var answer = listOf(GALLERY, STUBS)
    var store = StoreState.InStore
    val opened = mutableListOf<Triple<String, String?, Boolean>>()
    val installs = mutableListOf<ApkInfo>()

    override suspend fun known() = listOf(device)
    override fun live(deviceId: String): Flow<Snapshot> = emptyFlow()
    override suspend fun suggestions(deviceId: String, apps: suspend () -> List<AppInfo>) = Suggestions.Unavailable()
    override suspend fun history(deviceId: String) = emptyList<HistoryEntry>()
    override suspend fun apps(deviceId: String) = own
    override suspend fun fileHandlers(deviceId: String, folder: TvFolder, file: TvFile, mime: String) = answer
    override suspend fun openFile(deviceId: String, folder: TvFolder, file: TvFile, mime: String, packageName: String?, allowReading: Boolean): Boolean {
        opened += Triple(file.name, packageName, allowReading)
        return true
    }
    override suspend fun abis(deviceId: String) = listOf("armeabi-v7a", "armeabi")
    override suspend fun openStore(deviceId: String, packageName: String) = packageName == PLAYER.pkg
    override suspend fun launch(deviceId: String, packageName: String) = true
    override suspend fun storeState(deviceId: String, packageName: String) = store
    override suspend fun install(deviceId: String, apks: List<File>, apk: ApkInfo, allowDowngrade: Boolean, onProgress: (Progress) -> Unit): InstallResult {
        installs += apk
        return InstallResult.Done
    }
}

private val release = PlayerRelease("3.7.1", "armeabi-v7a", "https://get.videolan.org/vlc-android/3.7.1/",
    "https://get.videolan.org/vlc-android/3.7.1/VLC-Android-3.7.1-armeabi-v7a.apk", 47_073_346, "a".repeat(64))

/** The GitHub build's download: VideoLAN's build, or one that fails the check when not [signed]. */
private class Download(var signed: Boolean = true) : LauncherPick, PlayerDownload {
    var abis: List<String>? = null
    override suspend fun open(uri: Uri, launcher: KnownLauncher): PickedApp = throw IOException("not here")
    override suspend fun latestPlayer(abis: List<String>): PlayerRelease = release.also { this.abis = abis }
    override suspend fun fetchPlayer(release: PlayerRelease, onProgress: (Progress) -> Unit): PickedApp {
        if (!signed) throw WrongApk()
        return PickedApp(listOf(File("vlc.apk")), ApkInfo(PLAYER.pkg, 13070100, release.version), PLAYER.name)
    }
}

/** Open on TV in the Files tab: which app opens a file, and VLC offered when nothing plays it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenOnTvTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private var vm: TellyscopeViewModel? = null

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    /** [download]: the GitHub build's; null is the Play build, which downloads nothing. */
    private fun TestScope.vm(source: PlayerTv, download: Download? = Download()): TellyscopeViewModel {
        val v = TellyscopeViewModel(source, clock = { 0L }, launcherDownload = download)
        vm = v
        runCurrent()
        return v
    }

    @Test fun onlyMediaFilesOpen() {
        assertEquals(MediaKind.Video to "video/x-matroska", media("concert-2024.MKV"))
        assertEquals(MediaKind.Audio to "audio/mpeg", media("podcast-ep12.mp3"))
        assertEquals(MediaKind.Image, media("beach.jpg")?.first)
        assertNull(media("subtitles.srt"))
        assertNull(media("kodi-21.1-armeabi-v7a.apk"))
        assertNull(media("mp4"))
    }

    @Test fun theStubsNeverCountAndAGalleryIsntAPlayer() {
        val video = handlers(MediaKind.Video, listOf(GALLERY, STUBS, KODI))
        assertEquals(listOf(KODI), video.players)
        assertEquals(listOf(GALLERY), video.others)
        // For a picture, the gallery is just right.
        assertEquals(listOf(GALLERY), handlers(MediaKind.Image, listOf(GALLERY, STUBS)).players)
    }

    @Test fun vlcOnTheTvOpensItWithTheFoldersReadable() = with(test) {
        val tv = PlayerTv().apply { answer = listOf(GALLERY, PLAYER.pkg, STUBS) }
        val v = vm(tv)
        v.openFile(holiday)
        runCurrent()
        assertEquals(listOf(Triple(holiday.name, PLAYER.pkg, true)), tv.opened)
        assertEquals(Copy.openedFileIn(holiday.name, PLAYER.name), v.notice.value)
        assertNull(v.state.value.device.player)
    }

    @Test fun anotherPlayerOpensItWithoutAGrant() = with(test) {
        val tv = PlayerTv().apply { answer = listOf(KODI, STUBS) }
        val v = vm(tv)
        v.openFile(holiday)
        runCurrent()
        assertEquals(listOf(Triple(holiday.name, null, false)), tv.opened)
    }

    @Test fun onlyAGalleryOffersVlcWithTheGalleryAnyway() = with(test) {
        val tv = PlayerTv()
        val v = vm(tv)
        v.openFile(holiday)
        runCurrent()
        val p = v.state.value.device.player!!
        assertEquals(PlayerFlow.Step.Ask, p.step)
        assertEquals(PlayerStore.Play, p.store)
        assertTrue(p.download)
        assertEquals(GALLERY, p.other)
        assertEquals("Gallery", p.otherLabel)
        assertTrue(tv.opened.isEmpty())

        v.playerOther()
        runCurrent()
        assertEquals(listOf(Triple(holiday.name, GALLERY, false)), tv.opened)
        assertNull(v.state.value.device.player)
    }

    @Test fun nothingButTheStubsOffersVlcAlone() = with(test) {
        val tv = PlayerTv().apply { answer = listOf(STUBS) }
        val v = vm(tv)
        v.openFile(podcast)
        runCurrent()
        val p = v.state.value.device.player!!
        assertEquals(MediaKind.Audio, p.kind)
        assertNull(p.other)
    }

    @Test fun aPictureOpensInTheGallery() = with(test) {
        val tv = PlayerTv().apply { answer = listOf(GALLERY, STUBS) }
        val v = vm(tv)
        v.openFile(photo)
        runCurrent()
        assertEquals(listOf(Triple(photo.name, null, false)), tv.opened)
        assertNull(v.state.value.device.player)
    }

    @Test fun thePlayStoreThenTheFileOpensInVlc() = with(test) {
        val tv = PlayerTv()
        val v = vm(tv)
        v.openFile(holiday)
        runCurrent()
        v.playerFromStore()
        runCurrent()
        assertEquals(PlayerFlow.Step.Waiting, v.state.value.device.player?.step)
        assertTrue(v.state.value.device.player!!.opened)

        tv.store = StoreState.Installed
        advanceTimeBy(STORE_POLL_MS + 1)
        runCurrent()
        assertNull(v.state.value.device.player)
        assertEquals(listOf(Triple(holiday.name, PLAYER.pkg, true)), tv.opened)
    }

    @Test fun downloadDirectlyWarnsFirstThenInstallsAndOpens() = with(test) {
        val tv = PlayerTv()
        val download = Download()
        val v = vm(tv, download)
        v.openFile(holiday)
        runCurrent()
        v.playerDirect()
        runCurrent()
        assertEquals(PlayerFlow.Step.Warn, v.state.value.device.player?.step)
        assertEquals(release, v.state.value.device.player?.release)
        assertEquals(listOf("armeabi-v7a", "armeabi"), download.abis)
        assertTrue(tv.installs.isEmpty())

        v.playerDownload()
        runCurrent()
        assertEquals(listOf(PLAYER.pkg), tv.installs.map { it.packageName })
        assertNull(v.state.value.device.player)
        assertEquals(listOf(Triple(holiday.name, PLAYER.pkg, true)), tv.opened)
    }

    @Test fun aDownloadVideoLanDidntSignInstallsNothing() = with(test) {
        val tv = PlayerTv(own = listOf(gallery))
        val v = vm(tv, Download(signed = false))
        v.openFile(holiday)
        runCurrent()
        // No Play Store: Download and install, with no warning first.
        assertEquals(PlayerStore.None, v.state.value.device.player?.store)
        v.playerDownload()
        runCurrent()
        assertTrue(tv.installs.isEmpty())
        assertTrue(tv.opened.isEmpty())
        assertNull(v.state.value.device.player)
        assertEquals(Copy.wrongApk(PLAYER.name), v.notice.value)
    }

    @Test fun firesPlayBuildOffersTheAppstore() = with(test) {
        val tv = PlayerTv(tv("Amazon", "AFTKRT"), listOf(appstore))
        val v = vm(tv, download = null)
        tv.answer = listOf(STUBS)
        v.openFile(holiday)
        runCurrent()
        val p = v.state.value.device.player!!
        assertEquals(PlayerStore.Appstore, p.store)
        assertEquals(false, p.download)
    }

    @Test fun thePlayBuildWithNoStoreOpensTheGallery() = with(test) {
        val tv = PlayerTv(own = listOf(gallery))
        val v = vm(tv, download = null)
        v.openFile(holiday)
        runCurrent()
        assertNull(v.state.value.device.player)
        assertEquals(listOf(Triple(holiday.name, GALLERY, false)), tv.opened)
    }

    // get.videolan.org/vlc-android/last/ as nginx lists it, cut to two builds.
    private val listing = """
        <html>
        <head><title>Index of /videolan/vlc-android/last/</title></head>
        <body>
        <h1>Index of /videolan/vlc-android/last/</h1><hr><pre><a href="../">../</a>
        <a href="VLC-Android-3.7.1-arm64-v8a.apk">VLC-Android-3.7.1-arm64-v8a.apk</a>                    14-Jul-2026 07:20            47990842
        <a href="VLC-Android-3.7.1-arm64-v8a.apk.sha256">VLC-Android-3.7.1-arm64-v8a.apk.sha256</a>             14-Jul-2026 07:20                  98
        <a href="VLC-Android-3.7.1-armeabi-v7a.apk">VLC-Android-3.7.1-armeabi-v7a.apk</a>                  14-Jul-2026 07:20            47073346
        <a href="VLC-Android-3.7.1-armeabi-v7a.apk.sha256">VLC-Android-3.7.1-armeabi-v7a.apk.sha256</a>           14-Jul-2026 07:20                 100
        </pre><hr></body>
        </html>
    """.trimIndent()

    @Test fun theListingGivesTheFirstCpuTypeWithABuild() {
        val r = parseVlcListing(listing, listOf("x86", "armeabi-v7a", "armeabi"))!!
        assertEquals("3.7.1", r.version)
        assertEquals("armeabi-v7a", r.abi)
        assertEquals(47073346L, r.size)
        assertEquals("https://get.videolan.org/vlc-android/3.7.1/VLC-Android-3.7.1-armeabi-v7a.apk", r.apk)
        assertEquals("arm64-v8a", parseVlcListing(listing, listOf("arm64-v8a", "armeabi-v7a"))?.abi)
        assertNull(parseVlcListing(listing, listOf("x86_64")))
        assertNull(parseVlcListing(listing.replace("47073346", "999999999"), listOf("armeabi-v7a")))
    }

    @Test fun theSha256FileReadsAsSha256sumWritesIt() {
        val sha = "a6a7f940e4bd190c9a70f3f0dc97c1c402c10200af20edac7a1d452b24abd8a0"
        assertEquals(sha, parseSha256("$sha *VLC-Android-3.7.1-armeabi-v7a.apk\n"))
        assertNull(parseSha256("<html>not found</html>"))
    }
}
