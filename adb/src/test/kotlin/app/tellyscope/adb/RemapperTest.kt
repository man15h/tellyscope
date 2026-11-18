// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemapperTest {
    private val dex = byteArrayOf(1, 2, 3)

    @Test fun onlyColourAndAppButtonsMayBeMapped() {
        for (k in listOf("PROG_RED", "PROG_BLUE", "BUTTON_1", "BUTTON_16", "AVR_POWER", "APP_4")) assertTrue(RemapKeys.allowed(k), k)
        for (k in listOf("HOME", "POWER", "VOLUME_UP", "VOLUME_MUTE", "DPAD_CENTER", "BACK", "VOICE_ASSIST", "SETTINGS", "TV_INPUT", "BUTTON_17", "APP_5")) {
            assertFalse(RemapKeys.allowed(k), k)
        }
    }

    @Test fun mapFileLeavesOutAnythingOffTheList() {
        val map = RemapKeys.map(mapOf("PROG_RED" to "-", "HOME" to "-", "BUTTON_4" to "org.jellyfin.androidtv/.ui.startup.StartupActivity"))
        assertEquals("BUTTON_4\torg.jellyfin.androidtv/.ui.startup.StartupActivity\nPROG_RED\t-\n", map)
    }

    @Test fun learnedReadsEachAnswer() {
        assertEquals(Learned.Key("PROG_GREEN"), RemapKeys.learned("PROG_GREEN\n"))
        assertEquals(Learned.Refused("HOME"), RemapKeys.learned("!HOME\n"))
        assertEquals(Learned.Refused(null), RemapKeys.learned("!\n"))
        assertEquals(Learned.NoPress, RemapKeys.learned("none\n"))
        assertEquals(Learned.NotSent, RemapKeys.learned("moved\n"))
        // A name the script would never send as allowed still isn't taken.
        assertEquals(Learned.Refused("HOME"), RemapKeys.learned("HOME\n"))
        assertEquals(Learned.NoPress, RemapKeys.learned(""))
    }

    @Test fun brandOnlyWhereTheRemoteIsKnown() {
        assertEquals("Netflix", RemapKeys.brand("BUTTON_4", sony = true, fire = false))
        assertEquals("YouTube", RemapKeys.brand("APP_4", sony = false, fire = true))
        assertEquals(null, RemapKeys.brand("BUTTON_4", sony = false, fire = false))
        assertEquals(null, RemapKeys.brand("PROG_RED", sony = true, fire = false))
    }

    @Test fun startSendsTheJarAndMapAndWaitsForThePid() = runBlocking {
        val sh = FakeShell(mapOf("cd ${Remapper.DIR} && (" to ShellResult("started\n", 0)))
        assertEquals(RemapStart.Started, Remapper(sh, dex).start("PROG_RED\t-\n"))
        assertContentEquals(dex, sh.pushed["${Remapper.DIR}/remap.jar"])
        assertEquals("PROG_RED\t-\n", sh.pushed["${Remapper.DIR}/map"]!!.decodeToString())
        val run = sh.ran.last()
        assertTrue("--nice-name=${Remapper.NAME} ${Remapper.MAIN} ${Remapper.DIR}" in run, run)
        // Detached, with nothing left on adb's stream, or the shell call would wait for it.
        assertTrue("</dev/null >${Remapper.DIR}/log 2>&1 &)" in run, run)
    }

    @Test fun startThatNeverWritesItsPidFailsWithTheLog() = runBlocking {
        val sh = FakeShell(mapOf("cd ${Remapper.DIR} && (" to ShellResult("java.lang.SecurityException: grab\n", 0)))
        assertEquals(RemapStart.Failed("java.lang.SecurityException: grab"), Remapper(sh, dex).start(""))
    }

    @Test fun targetIsTheLaunchActivityOrThePackage() = runBlocking {
        val sh = FakeShell().apply {
            answer = {
                when {
                    "LEANBACK_LAUNCHER org.jellyfin.androidtv" in it -> ShellResult("priority=0 preferredOrder=0\norg.jellyfin.androidtv/.ui.startup.StartupActivity\n", 0)
                    else -> ShellResult("No activity found\n", 0)
                }
            }
        }
        val r = Remapper(sh, dex)
        assertEquals("org.jellyfin.androidtv/.ui.startup.StartupActivity", r.target("org.jellyfin.androidtv"))
        assertEquals("@com.example.tv", r.target("com.example.tv"))
    }
}
