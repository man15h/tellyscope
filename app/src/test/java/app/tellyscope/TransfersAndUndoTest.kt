// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.tellyscope.adb.Progress
import app.tellyscope.adb.TvFolder
import app.tellyscope.data.Action
import app.tellyscope.data.ConnectResult
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.PhoneFile
import app.tellyscope.data.PhoneStorage
import app.tellyscope.data.PickedApp
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.ui.Copy
import app.tellyscope.ui.TellyscopeViewModel
import kotlinx.coroutines.CompletableDeferred
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
import okio.Sink
import okio.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun tv(id: String) = Device(id, ScanHit(id, 5555, ScanKind.Classic, id, null), DeviceState.Online, null, null, null, 0L)

/** TV A online, TV B online once connected; sends wait on [pushGate], disables on [actGate]. */
private class Src : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    val known = mutableListOf(tv("tv-a"))
    var pushGate = CompletableDeferred<Unit>()
    var actGate: CompletableDeferred<Unit>? = null
    val acted = mutableListOf<String>()
    var history = emptyList<HistoryEntry>()

    override suspend fun known() = known.toList()

    override fun live(deviceId: String) = emptyFlow<Snapshot>()

    override suspend fun connect(hit: ScanHit): ConnectResult {
        known += tv(hit.ip)
        return ConnectResult.Online(tv(hit.ip))
    }

    override suspend fun push(deviceId: String, folder: TvFolder, name: String, size: Long?, source: Source, onProgress: (Progress) -> Unit): String {
        pushGate.await()
        return name
    }

    override suspend fun act(deviceId: String, action: Action, packageName: String, undoing: Long?): HistoryEntry {
        acted += packageName
        actGate?.await()
        return HistoryEntry(99, deviceId, 0, action, packageName, "x", null, null)
    }

    override suspend fun history(deviceId: String) = history
}

private object Phone : PhoneStorage {
    override suspend fun readApp(uris: List<Uri>): PickedApp = error("unused")
    override fun open(uri: Uri) = PhoneFile("clip.mp4", 3, Buffer().writeUtf8("abc"))
    override fun create(uri: Uri): Sink = Buffer()
    override fun delete(uri: Uri) = Unit
    override fun clearApp() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransfersAndUndoTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val source = Src()
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun aTvConnectingMidSendLeavesThePageAndItsDialog() = with(test) {
        vm = TellyscopeViewModel(source, Phone, clock = { 0L })
        runCurrent()
        assertEquals("tv-a", vm.state.value.selectedId)
        vm.push(Uri.parse("content://x/clip.mp4"))
        runCurrent()
        assertNotNull(vm.state.value.device.transfer)
        vm.connect(ScanHit("tv-b", 5555, ScanKind.Classic, "tv-b", null))
        runCurrent()
        assertEquals("tv-a", vm.state.value.selectedId)
        assertNotNull(vm.state.value.device.transfer)
    }

    @Test fun aSendEndingNeverClosesAnotherTvsDialog() = with(test) {
        vm = TellyscopeViewModel(source, Phone, clock = { 0L })
        runCurrent()
        vm.push(Uri.parse("content://x/clip.mp4"))
        runCurrent()
        val gateA = source.pushGate
        vm.connect(ScanHit("tv-b", 5555, ScanKind.Classic, "tv-b", null))
        runCurrent()
        source.pushGate = CompletableDeferred()
        vm.select("tv-b")
        runCurrent()
        assertNull(vm.state.value.device.transfer)
        vm.push(Uri.parse("content://x/clip.mp4"))
        runCurrent()
        assertNotNull(vm.state.value.device.transfer)
        gateA.complete(Unit)
        runCurrent()
        assertEquals("tv-b", vm.state.value.selectedId)
        assertNotNull(vm.state.value.device.transfer)
    }

    @Test fun anEntryAlreadyUndoneInHistoryIsNotUndoneAgain() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        val e = HistoryEntry(5, "tv-a", 0, Action.Disable, "com.x", "X", Action.Enable, null)
        source.history = listOf(e.copy(undoneAt = 1))
        vm.undo(e, "tv-a") // the snackbar's stale copy
        runCurrent()
        assertEquals(emptyList<String>(), source.acted)
    }

    @Test fun aSecondTapWhileUndoingDoesNothing() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        val e = HistoryEntry(5, "tv-a", 0, Action.Disable, "com.x", "X", Action.Enable, null)
        source.actGate = CompletableDeferred()
        vm.undo(e, "tv-a") // the snackbar
        runCurrent()
        vm.undo(e, "tv-a") // History, before the first one is back
        runCurrent()
        source.actGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("com.x"), source.acted)
    }

    @Test fun aSettingThisVersionDoesntKnowSaysSoInsteadOfCrashing() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        vm.undo(HistoryEntry(6, "tv-a", 0, Action.Change, "NO_SUCH_TWEAK", "X", Action.Change, null, mapOf("k" to "v")), "tv-a")
        runCurrent()
        assertEquals(Copy.CANT_UNDO, vm.notice.value)
    }
}
