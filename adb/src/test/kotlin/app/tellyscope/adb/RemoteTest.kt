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

    @Test fun hdmiOutputIsFixedVolume() {
        // A Fire TV stick: on the TV over HDMI, and on its own (TV off, no HDMI) its speaker stream.
        fun dump(devices: String) = """
            - STREAM_MUSIC:
               Min: 0
               Max: 15
               Current: 2 (speaker): 0, 400 (hdmi): 15, 40000000 (default): 3
               Devices: $devices
            - STREAM_ALARM:
               Max: 7
            --
              mFixedVolumeDevices=0x1000,0x800,0x400,0x200000,0x40000,0x40001,0x80000
              mExtVolumeController=null
        """.trimIndent()
        assertTrue(Remote.musicFixed(dump("hdmi")))
        assertFalse(Remote.musicFixed(dump("speaker")))
        assertFalse(Remote.musicFixed(dump("hdmi").substringBefore("--")))
        assertFalse(Remote.musicFixed("Error: dumpsys not allowed"))
        // Android 10 and older: one bitmask, here 0x20000|0x8000|0x4000|0x400.
        val mask = dump("hdmi").replace("0x1000,0x800,0x400,0x200000,0x40000,0x40001,0x80000", "0x2c400")
        assertTrue(Remote.musicFixed(mask))
        assertFalse(Remote.musicFixed(mask.replace("0x2c400", "0x2c000")))
        // In a list, 0x40001 is its own device, not bit 0x1 (here the code of "earpiece").
        val earpiece = dump("earpiece").replace("400 (hdmi)", "1 (earpiece)")
        assertFalse(Remote.musicFixed(earpiece))
    }

    @Test fun volumeFixedReadsTheAudioDump() = runTest {
        val dump = """
            - STREAM_MUSIC:
               Max: 15
               Current: 2 (speaker): 0, 400 (hdmi): 15
               Devices: hdmi
            --
              mFixedVolumeDevices=0x400
        """.trimIndent()
        val read = "dumpsys audio 2>/dev/null | grep -e mFixedVolumeDevices -e '^- STREAM_MUSIC:' -A12"
        assertTrue(Remote(FakeShell(mapOf(read to ShellResult(dump, 0)))).volumeFixed())
        assertFalse(Remote(FakeShell(mapOf(read to ShellResult(dump.replace("Devices: hdmi", "Devices: speaker"), 0)))).volumeFixed())
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

    @Test fun volumeTapIsOneJumpThroughTheHelper() = runBlocking { // see KeyHelperTest
        val sh = FakeShell().apply { execOutput = "ready\n1\n" }
        assertTrue(Remote(sh, KeyHelper(sh, byteArrayOf(1))).volume(RemoteKey.VOLUME_UP, 7))
        assertEquals("v7\n", sh.execInput.readUtf8())
        assertTrue(sh.ran.none { it.startsWith("input") })
    }

    @Test fun volumeThatDidNotMoveSendsTheKeys() = runBlocking {
        // A fixed or HDMI-CEC output: the set changes nothing, and the keys reach the speaker.
        val sh = FakeShell().apply { execOutput = "ready\n0\n1\n1\n1\n" }
        assertTrue(Remote(sh, KeyHelper(sh, byteArrayOf(1))).volume(RemoteKey.VOLUME_DOWN, 3))
        assertEquals("v-3\n25\n25\n25\n", sh.execInput.readUtf8())
    }

    @Test fun volumeWithoutTheHelperSendsTheKeys() = runTest {
        val sh = FakeShell()
        assertTrue(Remote(sh).volume(RemoteKey.VOLUME_UP, 2))
        assertEquals(List(2) { "input keyevent 24" }, sh.ran)
    }

    @Test fun updatesOpenAndroidsUpdateScreen() = runTest {
        val sh = FakeShell()
        assertEquals(UpdateScreen.UPDATES, Remote(sh).openUpdates(fireOs = false))
        assertEquals(listOf("am start -a android.settings.SYSTEM_UPDATE_SETTINGS 2>&1"), sh.ran)
    }

    @Test fun anAndroidTvWithoutAnUpdateScreenTriesNothingElse() = runTest {
        val sh = FakeShell(mapOf("am start" to ShellResult("Error: Activity not started, unable to resolve Intent", 0)))
        assertEquals(UpdateScreen.NONE, Remote(sh).openUpdates(fireOs = false))
        assertEquals(1, sh.ran.size)
    }

    @Test fun aFireTvOpensAboutWhenItCan() = runTest {
        val sh = FakeShell(mapOf("am start -a android.settings.SYSTEM_UPDATE_SETTINGS" to ShellResult("Error: Activity not started, unable to resolve Intent", 0)))
        assertEquals(UpdateScreen.UPDATES, Remote(sh).openUpdates(fireOs = true))
        assertEquals("am start -a android.settings.DEVICE_INFO_SETTINGS 2>&1", sh.ran.last())
    }

    @Test fun aFireTvThatGuardsAboutOpensSettings() = runTest {
        val sh = FakeShell(
            mapOf(
                "am start -a" to ShellResult("Error: Activity not started, unable to resolve Intent", 0),
                "am start -n ${Remote.FIRE_SETTINGS[0]}" to
                    ShellResult("java.lang.SecurityException: Permission Denial: starting Intent requires com.amazon.tv.permission.LAUNCHER_SETTINGS", 255),
            ),
        )
        assertEquals(UpdateScreen.FIRE_SETTINGS, Remote(sh).openUpdates(fireOs = true))
        assertEquals("am start -n ${Remote.FIRE_SETTINGS[1]} 2>&1", sh.ran.last())
    }

    @Test fun aFireTvThatOpensNothingSaysWhereToLook() = runTest {
        val sh = FakeShell(mapOf("am start" to ShellResult("Error: Activity class does not exist.", 0)))
        assertEquals(UpdateScreen.FIRE_NONE, Remote(sh).openUpdates(fireOs = true))
        assertEquals(2 + Remote.FIRE_SETTINGS.size, sh.ran.size)
    }
}
