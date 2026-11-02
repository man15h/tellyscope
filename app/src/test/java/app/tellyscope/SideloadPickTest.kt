// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.tellyscope.adb.ApkInfo
import app.tellyscope.adb.InstalledVersion
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.PhoneStorage
import app.tellyscope.data.PickedApp
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.ui.Copy
import app.tellyscope.ui.SideloadUi
import app.tellyscope.ui.TellyscopeViewModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One online TV whose version check fails when [unreachable]; [asked] counts the checks. */
private class VersionCheckTv : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    var unreachable = false
    var asked = 0

    override suspend fun known() = listOf(Device("tv", ScanHit("tv", 5555, ScanKind.Classic, "tv", null), DeviceState.Online, null, null, null, 0L))

    override fun live(deviceId: String) = emptyFlow<Snapshot>()

    override suspend fun installed(deviceId: String, packageName: String): InstalledVersion? {
        asked++
        if (unreachable) throw IOException("link gone")
        return null
    }
}

/** A phone whose picked file reads when [readable], and fails like a revoked or vanished file otherwise. */
private class Picked(private val readable: Boolean) : PhoneStorage {
    override suspend fun readApp(uris: List<Uri>) =
        if (readable) PickedApp(listOf(File("app.apk")), ApkInfo("com.example.app", 1, "1.0"), "Example") else throw IOException("permission revoked")
    override fun open(uri: Uri) = error("unused")
    override fun create(uri: Uri) = Buffer()
    override fun delete(uri: Uri) = Unit
    override fun clearApp() = Unit
}

/** A failed pick names what failed: the phone's file or the TV, never "lost partway" before anything was sent. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SideloadPickTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val tv = VersionCheckTv()
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.pick(phone: PhoneStorage): SideloadUi? {
        vm = TellyscopeViewModel(tv, phone, clock = { 0L })
        runCurrent()
        vm.pickedApp(listOf(Uri.parse("content://x/app.apk")))
        runCurrent()
        return vm.state.value.device.sideload
    }

    @Test fun aFileThePhoneCantReadSaysSoAndNeverAsksTheTv() = with(test) {
        val ui = pick(Picked(readable = false))
        assertEquals(SideloadUi.Step.Failed, ui?.step)
        assertEquals(Copy.CANT_READ, ui?.error)
        assertEquals(0, tv.asked)
    }

    @Test fun aTvThatDoesntAnswerTheVersionCheckIsUnreachableNotLost() = with(test) {
        tv.unreachable = true
        val ui = pick(Picked(readable = true))
        assertEquals(SideloadUi.Step.Failed, ui?.step)
        assertEquals(Copy.UNREACHABLE, ui?.error)
        assertEquals(1, tv.asked)
    }

    @Test fun aReadableFileOnAnAnsweringTvIsReady() = with(test) {
        val ui = pick(Picked(readable = true))
        assertEquals(SideloadUi.Step.Ready, ui?.step)
    }
}
