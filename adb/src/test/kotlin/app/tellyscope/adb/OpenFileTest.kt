// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFileTest {
    @Test fun theAddressEncodesWhatTheShellAndAUriCantTake() {
        assertEquals("file:///sdcard/Download/holiday-2025.mp4", OpenFile.fileUri(TvFolder.DOWNLOAD, "holiday-2025.mp4"))
        assertEquals("file:///sdcard/Movies/it%27s%20here%20%281%29.mkv", OpenFile.fileUri(TvFolder.MOVIES, "it's here (1).mkv"))
        assertEquals("file:///sdcard/Download/%C3%A9t%C3%A9.mp3", OpenFile.fileUri(TvFolder.DOWNLOAD, "été.mp3"))
    }

    // `cmd package query-activities --brief` on the MoGo projector with no player installed.
    @Test fun handlersAreThePackagesTheTvNames() = runBlocking {
        val shell = FakeShell(
            mapOf(
                "cmd package query-activities" to ShellResult(
                    "3 activities found:\n  Activity #0:\n    com.android.gallery3d/.app.MovieActivity\n" +
                        "  Activity #1:\n    com.internet.tvbrowser/.BrowserActivity\n" +
                        "  Activity #2:\n    com.google.android.tv.frameworkpackagestubs/.Stubs\$BrowserStub\n",
                    0,
                ),
            ),
        )
        val found = OpenFile(shell).handlers(TvFolder.DOWNLOAD, "holiday 2025.mp4", "video/mp4")
        assertEquals(listOf("com.android.gallery3d", "com.internet.tvbrowser", "com.google.android.tv.frameworkpackagestubs"), found)
        assertTrue(shell.ran.single().contains("-d 'file:///sdcard/Download/holiday%202025.mp4' -t video/mp4"))
    }

    @Test fun openNamesThePlayerAndReadsTheRefusal() = runBlocking {
        val shell = FakeShell()
        assertTrue(OpenFile(shell).open(TvFolder.DOWNLOAD, "a.mp4", "video/mp4", "org.videolan.vlc"))
        assertTrue(shell.ran.single().endsWith("-t video/mp4 -p org.videolan.vlc 2>&1"))
        val refused = FakeShell(mapOf("am start" to ShellResult("Error: Activity not started, unable to resolve Intent", 0)))
        assertFalse(OpenFile(refused).open(TvFolder.DOWNLOAD, "a.mp4", "video/mp4", null))
    }

    @Test fun readingIsGrantedOnlyToAPackage() = runBlocking {
        val shell = FakeShell()
        OpenFile(shell).allowReading("org.videolan.vlc")
        val line = shell.ran.single()
        assertTrue(line.contains("pm grant org.videolan.vlc android.permission.READ_MEDIA_VIDEO"))
        assertTrue(line.contains("pm grant org.videolan.vlc android.permission.READ_EXTERNAL_STORAGE"))
        assertTrue(line.contains("appops set org.videolan.vlc MANAGE_EXTERNAL_STORAGE allow"))
        assertTrue(runCatching { OpenFile(shell).allowReading("vlc; reboot") }.isFailure)
    }

    @Test fun abisComeBestFirst() = runBlocking {
        val shell = FakeShell(mapOf("getprop ro.product.cpu.abilist" to ShellResult("armeabi-v7a,armeabi\n", 0)))
        assertEquals(listOf("armeabi-v7a", "armeabi"), OpenFile(shell).abis())
    }
}
