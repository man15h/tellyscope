// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteTest {

    @Test fun keysSendTheirCode() = runTest {
        val sh = FakeShell()
        assertTrue(Remote(sh).press(RemoteKey.DOWN))
        assertTrue(Remote(sh).press(RemoteKey.MUTE))
        assertEquals(listOf("input keyevent 20", "input keyevent 164"), sh.ran)
    }

    @Test fun keysGoThroughTheHelperWhileItRuns() = runBlocking { // see KeyHelperTest
        val sh = FakeShell().apply { execOutput = "ready\n1\n" }
        assertTrue(Remote(sh, KeyHelper(sh, byteArrayOf(1))).press(RemoteKey.LEFT))
        assertEquals("21\n", sh.execInput.readUtf8())
        assertTrue(sh.ran.none { it.startsWith("input") })
    }

    @Test fun textGoesThroughTheHelperWhileItRuns() = runBlocking { // see KeyHelperTest
        val sh = FakeShell().apply { execOutput = "ready\n1\n" }
        assertTrue(Remote(sh, KeyHelper(sh, byteArrayOf(1))).type("hello tv"))
        assertEquals("thello tv\n", sh.execInput.readUtf8())
        assertTrue(sh.ran.none { it.startsWith("input") })
    }

    @Test fun textFallsBackToInputWhenTheHelperCannotStart() = runBlocking { // see KeyHelperTest
        val sh = FakeShell().apply { execOutput = "" }
        assertTrue(Remote(sh, KeyHelper(sh, byteArrayOf(1))).type("hi"))
        assertEquals(Remote.typeCommand("hi"), sh.ran.last())
    }

    @Test fun keysFallBackToInputWhenTheHelperCannotStart() = runBlocking { // see KeyHelperTest
        val sh = FakeShell().apply { execOutput = "" }
        assertTrue(Remote(sh, KeyHelper(sh, byteArrayOf(1))).press(RemoteKey.HOME))
        assertEquals("input keyevent 3", sh.ran.last())
    }

    @Test fun backspaceIsOneDelete() = runTest {
        val sh = FakeShell()
        assertTrue(Remote(sh).press(RemoteKey.DEL))
        assertEquals(listOf("input keyevent 67"), sh.ran)
    }

    @Test fun backspaceGoesThroughTheHelperWhileItRuns() = runBlocking { // see KeyHelperTest
        val sh = FakeShell().apply { execOutput = "ready\n1\n" }
        assertTrue(Remote(sh, KeyHelper(sh, byteArrayOf(1))).press(RemoteKey.DEL))
        assertEquals("67\n", sh.execInput.readUtf8())
        assertTrue(sh.ran.none { it.startsWith("input") })
    }

    @Test fun fireHomeStartsTheDefaultLauncher() = runTest {
        val sh = FakeShell()
        assertTrue(Remote(sh).home("com.spocky.projengmenu/.ui.home.MainActivity"))
        assertEquals(
            listOf("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n 'com.spocky.projengmenu/.ui.home.MainActivity' 2>&1"),
            sh.ran,
        )
    }

    @Test fun fireHomeWithoutALauncherSendsTheKey() = runTest {
        val sh = FakeShell()
        assertTrue(Remote(sh).home(null))
        assertEquals(listOf("input keyevent 3"), sh.ran)
    }

    @Test fun fireHomeFallsBackToTheKeyWhenTheTvWontStartIt() = runTest {
        val sh = FakeShell(mapOf("am start" to ShellResult("Error: Activity class {x/.Gone} does not exist.", 0)))
        assertTrue(Remote(sh).home("x/.Gone"))
        assertEquals("input keyevent 3", sh.ran.last())
    }

    @Test fun fireHomeNeverPutsAnOddNameOnTheShellLine() = runTest {
        val sh = FakeShell()
        assertTrue(Remote(sh).home("x/.A'; reboot; '"))
        assertEquals(listOf("input keyevent 3"), sh.ran)
    }

    @Test fun keyRefusedByTheTvIsFalse() = runTest {
        val sh = FakeShell(mapOf("input keyevent" to ShellResult("Error: Unknown command", 1)))
        assertFalse(Remote(sh).press(RemoteKey.HOME))
    }

    @Test fun spacesBecomePercentS() {
        assertEquals("input text 'star%swars'", Remote.typeCommand("star wars"))
    }

    @Test fun percentBeforeASpaceStillTypesBoth() {
        // input text only rewrites "%s"; the leading % of "%%s" stays a plain %.
        assertEquals("input text '50%%soff'", Remote.typeCommand("50% off"))
    }

    @Test fun shellCharactersStayInsideTheQuotes() {
        assertEquals("input text '\$(reboot);`id`&&\"x\"|\\'", Remote.typeCommand("\$(reboot);`id`&&\"x\"|\\"))
    }

    @Test fun singleQuoteIsClosedEscapedAndReopened() {
        assertEquals("input text 'it'\\''s'", Remote.typeCommand("it's"))
    }

    @Test fun refusesWhatInputTextCannotType() {
        listOf("", "café", "line\nbreak", "tab\there", "emoji 🙂", "100%sure", "x".repeat(Remote.MAX_TEXT + 1))
            .forEach { assertFalse(Remote.typeable(it), it) }
        assertTrue(Remote.typeable("x".repeat(Remote.MAX_TEXT)))
        assertFailsWith<IllegalArgumentException> { Remote.typeCommand("café") }
    }

    @Test fun typeRunsTheQuotedCommand() = runTest {
        val sh = FakeShell()
        assertTrue(Remote(sh).type("hello there"))
        assertEquals(listOf("input text 'hello%sthere'"), sh.ran)
    }

    @Test fun rebootUsesTheAdbService() = runTest {
        val sh = FakeShell()
        assertTrue(Remote(sh).reboot())
        assertTrue(sh.rebooted)
        assertTrue(sh.ran.isEmpty())
    }

    @Test fun refusedServiceFallsBackToSvc() = runTest {
        val sh = FakeShell().apply { rebootFails = IllegalStateException("closed") }
        assertTrue(Remote(sh).reboot())
        assertEquals(listOf("svc power reboot 2>&1"), sh.ran)
    }

    @Test fun svcSayingNoIsFalse() = runTest {
        val sh = FakeShell(mapOf("svc power" to ShellResult("Failed to reboot: permission denied\n", 0)))
            .apply { rebootFails = IllegalStateException("closed") }
        assertFalse(Remote(sh).reboot())
    }

    @Test fun linkDroppingDuringFallbackCountsAsRestarting() = runTest {
        val sh = FakeShell().apply {
            rebootFails = IllegalStateException("closed")
            failWith = IOException("connection reset")
        }
        assertTrue(Remote(sh).reboot())
    }

    @Test fun volumeMaxIsReadFromTheMusicStream() {
        val dump = """
            - STREAM_SYSTEM:
               Min: 0
               Max: 7
            - STREAM_MUSIC:
               Muted: false
               Muted Internally: false
               Min: 0
               Max: 15
               streamVolume:15
               Current: 2 (speaker): 3, 400 (hdmi): 15, 40000000 (default): 3
               Devices: hdmi
            - STREAM_ALARM:
               Max: 7
        """.trimIndent()
        assertEquals(15, Remote.musicMax(dump))
        assertEquals(null, Remote.musicMax("Error: dumpsys not allowed"))
    }

    @Test fun volumeTapsCoverAboutFifteenSteps() {
        assertEquals(1, Remote.volumeKeys(15))
        assertEquals(1, Remote.volumeKeys(7))
        assertEquals(2, Remote.volumeKeys(30))
        assertEquals(3, Remote.volumeKeys(50))
        assertEquals(7, Remote.volumeKeys(100))
        assertEquals(1, Remote.volumeKeys(null))
    }

    @Test fun volumeKeysRepeatAndStopAtTheFirstRefusal() = runTest {
        val sh = FakeShell()
        assertTrue(Remote(sh).press(RemoteKey.VOLUME_UP, 3))
        assertEquals(List(3) { "input keyevent 24" }, sh.ran)
        val refused = FakeShell(mapOf("input keyevent 25" to ShellResult("", 1)))
        assertFalse(Remote(refused).press(RemoteKey.VOLUME_DOWN, 3))
        assertEquals(listOf("input keyevent 25"), refused.ran)
    }
}
