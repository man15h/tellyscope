// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.lifecycle.viewModelScope
import app.tellyscope.adb.RemoteKey
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.data.UiText
import app.tellyscope.ui.Copy
import app.tellyscope.ui.TellyscopeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One TV; what reached it is recorded in order. */
private class TextBox : DeviceSource by FakeDeviceSource(FakeDeviceSource.Scenario.Empty, clock = { 0L }) {
    val sent = mutableListOf<String>()
    var takes = true

    override suspend fun known() = listOf(
        Device("tv", ScanHit("tv", 5555, ScanKind.Classic, "tv", null), DeviceState.Online, null, null, null, 0L),
    )

    override fun live(deviceId: String) = emptyFlow<Snapshot>()

    override suspend fun type(deviceId: String, text: String): Boolean {
        sent += "type $text"
        return takes
    }

    override suspend fun press(deviceId: String, key: RemoteKey): Boolean {
        sent += key.name
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteBackspaceTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val tv = TextBox()
    private lateinit var vm: TellyscopeViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = TellyscopeViewModel(tv, clock = { 0L })
        test.runCurrent()
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun press(key: RemoteKey): Boolean {
        var took = false
        test.launch { took = vm.press(key) }
        test.runCurrent()
        return took
    }

    @Test fun anEmptyBoxSendsNothing() {
        // On the TV, Delete in an empty box is Back: YouTube closes.
        assertFalse(press(RemoteKey.DEL))
        assertEquals(emptyList<String>(), tv.sent)
    }

    @Test fun deletesWhatWasTypedOneLetterAPressThenStops() {
        vm.type("cat")
        test.runCurrent()
        repeat(3) { assertTrue(press(RemoteKey.DEL)) }
        // The fourth would be Back; a held key stops here.
        assertFalse(press(RemoteKey.DEL))
        assertEquals(listOf("type cat", "DEL", "DEL", "DEL"), tv.sent)
    }

    @Test fun deletesPastedTextOneCharacterAPress() {
        // Cyrillic is one char a letter; the emoji is two chars but one Backspace.
        vm.type("да 🙂")
        test.runCurrent()
        repeat(4) { assertTrue(press(RemoteKey.DEL)) }
        assertFalse(press(RemoteKey.DEL))
    }

    @Test fun aPasteTheBoxRefusedSaysSoAndLeavesNothingToDelete() {
        tv.takes = false
        vm.type("да")
        test.runCurrent()
        assertEquals(Copy.cantPaste("да"), vm.notice.value)
        assertFalse(press(RemoteKey.DEL))
    }

    @Test fun aRefusedEmojiIsNamedWhole() {
        assertEquals(UiText(R.string.copy_cant_paste, "🙂"), Copy.cantPaste("ok 🙂"))
    }

    @Test fun anotherKeyForgetsTheTypedText() {
        vm.type("cat")
        test.runCurrent()
        press(RemoteKey.DOWN)
        assertFalse(press(RemoteKey.DEL))
        assertEquals(listOf("type cat", "DOWN"), tv.sent)
    }

    @Test fun closingTheRemoteForgetsIt() {
        vm.type("cat")
        test.runCurrent()
        vm.closeRemote()
        assertFalse(press(RemoteKey.DEL))
    }
}
