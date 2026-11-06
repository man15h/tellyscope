// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * runBlocking, not runTest: the helper's reads run on a real IO thread under real timeouts, and
 * runTest's virtual clock would fire those timeouts before the fake pipe answers.
 */
class KeyHelperTest {
    private val dex = byteArrayOf(1, 2, 3)

    @Test fun startsOnceAndSendsEachCode() = runBlocking {
        val sh = FakeShell().apply { execOutput = "ready\n1\n1\n" }
        val keys = KeyHelper(sh, dex)
        assertEquals(true, keys.press(20))
        assertEquals(true, keys.press(23))
        assertContentEquals(dex, sh.pushed[KeyHelper.PATH])
        val run = sh.execs.single()
        assertTrue(run.startsWith("CLASSPATH=${KeyHelper.PATH} app_process / ${KeyHelper.MAIN} ${KeyHelper.PATH} "))
        assertTrue(run.endsWith("rm -f ${KeyHelper.PATH}"))
        assertEquals("20\n23\n", sh.execInput.readUtf8())
    }

    @Test fun textGoesOnItsOwnLine() = runBlocking {
        val sh = FakeShell().apply { execOutput = "ready\n1\n1\n" }
        val keys = KeyHelper(sh, dex)
        assertEquals(true, keys.press(20))
        assertEquals(true, keys.type(" hello tv "))
        assertEquals("20\nt hello tv \n", sh.execInput.readUtf8())
    }

    @Test fun volumeTheHelperCannotReachKeepsTheHelper() = runBlocking {
        // "0" to a volume line means send the keys, not a broken helper: the next key still goes through it.
        val sh = FakeShell().apply { execOutput = "ready\n0\n1\n" }
        val keys = KeyHelper(sh, dex)
        assertEquals(false, keys.volume(-7))
        assertEquals(true, keys.press(25))
        assertEquals("v-7\n25\n", sh.execInput.readUtf8())
        assertEquals(1, sh.execs.size)
    }

    @Test fun nonLatinTextReachesTheHelperWhole() = runBlocking {
        // The helper pastes what its keymap can't type; the pipe carries it as UTF-8.
        val sh = FakeShell().apply { execOutput = "ready\n1\n" }
        assertEquals(true, KeyHelper(sh, dex).type("Привет مرحبا"))
        assertEquals("tПривет مرحبا\n", sh.execInput.readUtf8())
    }

    @Test fun tvRefusingAKeyIsFalse() = runBlocking {
        val sh = FakeShell().apply { execOutput = "ready\n0\n" }
        assertEquals(false, KeyHelper(sh, dex).press(4))
    }

    @Test fun helperThatNeverSaysReadyIsNotTriedAgain() = runBlocking {
        // An OEM that blocks app_process for the shell user: the run ends and prints nothing.
        val sh = FakeShell().apply { execOutput = "" }
        val keys = KeyHelper(sh, dex)
        assertNull(keys.press(20))
        assertNull(keys.press(20))
        assertEquals(1, sh.execs.size)
        assertTrue(sh.execClosed)
        assertEquals("rm -f ${KeyHelper.PATH}", sh.ran.last())
    }

    @Test fun refusedExecFallsBack() = runBlocking {
        val sh = FakeShell()
        assertNull(KeyHelper(sh, dex).press(20))
        assertEquals("rm -f ${KeyHelper.PATH}", sh.ran.last())
    }

    @Test fun helperThatDiesHandsBackToTheFallback() = runBlocking {
        val sh = FakeShell().apply { execOutput = "ready\n1\n" }
        val keys = KeyHelper(sh, dex)
        assertEquals(true, keys.press(20))
        assertNull(keys.press(20))
        assertNull(keys.press(20))
        assertEquals(1, sh.execs.size)
        assertTrue(sh.execClosed)
    }

    @Test fun closeEndsTheHelper() = runBlocking {
        val sh = FakeShell().apply { execOutput = "ready\n1\n" }
        val keys = KeyHelper(sh, dex)
        keys.press(20)
        assertFalse(sh.execClosed)
        keys.close()
        assertTrue(sh.execClosed)
    }
}
