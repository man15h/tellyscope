// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceList
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ListEntry
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.data.Suggestions
import app.tellyscope.ui.SourceCheck
import app.tellyscope.ui.TellyscopeViewModel
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One online TV whose lists load, except [broken] ones and while [down]. */
private class OneTv : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    val entries = listOf(ListEntry("tcl", "TCL C-series", null, null, "tcl.json", ""), ListEntry("sony", "Sony Bravia 2023", null, null, "sony.json", ""))
    var picked: String? = "tcl"
    val broken = mutableSetOf<String>()
    var down = false
    var sourceGate: CompletableDeferred<Unit>? = null
    var sourceRefuses = false
    var sourceSet: String? = null

    override suspend fun known() =
        listOf(Device("tv", ScanHit("tv", 5555, ScanKind.Classic, "tv", null), DeviceState.Online, null, null, null, 0L))

    override fun live(deviceId: String): Flow<Snapshot> = emptyFlow()

    override suspend fun apps(deviceId: String) = emptyList<AppInfo>()

    override suspend fun suggestions(deviceId: String, apps: suspend () -> List<AppInfo>): Suggestions {
        val id = picked
        if (down || id == null || id in broken) return Suggestions.Unavailable(entries)
        val name = entries.first { it.id == id }.name
        return Suggestions.Ready(null, DeviceList(id, name, null, null, emptySet(), emptyList()), null, emptyList(), entries)
    }

    override suspend fun pickList(deviceId: String, listId: String?): String? = picked.also { picked = listId }

    override suspend fun listSource() = sourceSet

    override suspend fun setListSource(url: String?) {
        sourceGate?.await()
        if (sourceRefuses) throw IllegalStateException("no index.json at that address")
        sourceSet = url
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ListsAndSourceTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val source = OneTv()
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun shownList() = (vm.state.value.device.lists as? Suggestions.Ready)?.list?.id

    @Test fun aPickThatDoesntLoadKeepsTheListItHad() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        assertEquals("tcl", shownList())

        source.broken += "sony"
        vm.pickList("sony")
        runCurrent()
        assertEquals("tcl", shownList())
        assertEquals("tcl", source.picked)
        assertEquals("Couldn't load the Sony Bravia 2023 list. Still on TCL C-series.", vm.notice.value?.text())
    }

    @Test fun aFailedListOffersEveryListAndTryAgainLoadsIt() = with(test) {
        source.down = true
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        val failed = vm.state.value.device.lists
        assertTrue(failed is Suggestions.Unavailable)
        // "Not this TV?" still has every list to pick from.
        assertEquals(source.entries, (failed as Suggestions.Unavailable).devices)

        source.down = false
        vm.retryLists()
        assertNull(vm.state.value.device.lists) // loading
        runCurrent()
        assertEquals("tcl", shownList())
    }

    @Test fun aRefusedSourceSaysWhyAndChangesNothing() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        source.sourceGate = CompletableDeferred()
        source.sourceRefuses = true
        vm.setListSource("https://example.com/lists/")
        runCurrent()
        assertEquals(SourceCheck.Checking, vm.state.value.sourceCheck)

        source.sourceGate!!.complete(Unit)
        runCurrent()
        val refused = vm.state.value.sourceCheck
        assertTrue(refused is SourceCheck.Refused)
        assertEquals("No index.json at that address", (refused as SourceCheck.Refused).why.text())
        assertNull(vm.state.value.listSource)
        assertEquals("tcl", shownList())
    }

    @Test fun cancelStopsTheCheck() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        source.sourceGate = CompletableDeferred()
        vm.setListSource("https://example.com/lists/")
        runCurrent()
        vm.cancelListSource()
        source.sourceGate!!.complete(Unit)
        runCurrent()
        assertNull(vm.state.value.sourceCheck)
        assertNull(source.sourceSet)
    }

    @Test fun aTakenSourceClosesTheCheckAndReloads() = with(test) {
        vm = TellyscopeViewModel(source, clock = { 0L })
        runCurrent()
        vm.setListSource("https://example.com/lists/")
        runCurrent()
        assertNull(vm.state.value.sourceCheck)
        assertEquals("https://example.com/lists/", vm.state.value.listSource)
        assertEquals("tcl", shownList())
    }
}
