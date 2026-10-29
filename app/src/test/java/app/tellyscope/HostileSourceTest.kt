// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.Action as AdbAction
import app.tellyscope.adb.Actions
import app.tellyscope.adb.ShellResult
import app.tellyscope.data.DeviceLists
import app.tellyscope.data.LISTS_BASE
import app.tellyscope.data.Png
import app.tellyscope.data.SourceResult
import app.tellyscope.data.download
import app.tellyscope.data.grant
import app.tellyscope.data.parseIndex
import app.tellyscope.data.parseLabels
import app.tellyscope.data.parseLaunchers
import app.tellyscope.data.parseList
import app.tellyscope.data.parseStreaming
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * A list source written by an attacker: a fork, where the tellyscope-devices checks never run.
 * Each test serves one hostile file and checks it never reaches the TV's shell, the disk outside
 * the cache, the bitmap decoder or the screen as sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostileSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ran = mutableListOf<String>()
    private val actions = Actions { command -> ran += command; ShellResult("", 0) }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The files of a source at [LISTS_BASE]; every URL asked for is kept in [asked]. */
    private val asked = mutableListOf<String>()
    private fun served(files: Map<String, ByteArray>): suspend (String) -> ByteArray = { url ->
        asked += url
        files[url.removePrefix(LISTS_BASE)] ?: throw IOException("404 $url")
    }

    private fun index(logos: Map<String, Pair<String, ByteArray>> = emptyMap(), lists: Map<String, Pair<String, ByteArray>> = emptyMap()): ByteArray {
        fun files(m: Map<String, Pair<String, ByteArray>>) = m.entries.joinToString(",") { (k, v) -> "\"$k\": {\"file\": \"${v.first}\", \"sha256\": \"${sha256(v.second)}\"}" }
        return """{"schema": 1, "devices": [], "lists": {${files(lists)}}, "logos": {${files(logos)}}}""".toByteArray()
    }

    /** A PNG header (signature and IHDR) claiming [w] × [h], padded to [size] bytes. */
    private fun png(w: Int, h: Int, size: Int = 64): ByteArray {
        val b = ByteArray(maxOf(size, 33))
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b)
        byteArrayOf(0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()).copyInto(b, 8)
        fun int(v: Int, at: Int) = (0..3).forEach { b[at + it] = (v shr (24 - 8 * it)).toByte() }
        int(w, 16)
        int(h, 20)
        b[24] = 8 // bit depth
        b[25] = 6 // RGBA
        return b
    }

    // a.b;reboot

    @Test fun aPackageWithAShellCommandIsDroppedFromEveryList() {
        val evil = "a.b;reboot"
        assertTrue(parseLaunchers("""{"schema": 1, "launchers": [{"package": "$evil", "name": "Evil", "about": "x"}]}""").isEmpty())
        assertTrue(parseLabels("""{"schema": 1, "groups": [{"labels": {"$evil": "Evil"}}]}""").isEmpty())
        assertTrue(parseStreaming("""{"schema": 1, "groups": [{"name": "G", "services": [{"name": "Evil", "packages": ["$evil"], "color": "#000000", "logo": "evil"}]}]}""").isEmpty())
        val list = parseList("""{"schema": 1, "id": "x", "name": "X", "groups": [{"title": "T", "packages": [{"pkg": "$evil"}]}]}""", "x")
        assertTrue(list!!.groups.isEmpty())
    }

    @Test fun aPackageWithAShellCommandNeverReachesTheShell() = runTest {
        for (action in AdbAction.entries) {
            try {
                actions.run(action, "a.b;reboot")
                fail("$action ran")
            } catch (_: IllegalArgumentException) {
            }
        }
        for (call in listOf<suspend () -> Unit>(
            { actions.makeHome("a.b;reboot") },
            { actions.openStore("a.b;reboot") },
            { actions.installed("a.b;reboot") },
            { actions.setHome("a.b/.Main;reboot") },
            { actions.handOff(null, "a.b/.Main';reboot;'") },
        )) {
            try {
                call()
                fail("ran")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(emptyList<String>(), ran)
    }

    // ../../x.png

    @Test fun aLogoPathOutOfTheCacheIsDroppedAndNeverFetched() = runTest {
        val dir = tmp.newFolder("lists")
        val bytes = png(10, 10)
        val lists = DeviceLists(dir, served(mapOf("index.json" to index(logos = mapOf("x" to ("logos/../../x.png" to bytes))), "logos/../../x.png" to bytes)))
        assertTrue(lists.index()!!.logos.isEmpty())
        assertNull(lists.logo("x"))
        assertEquals(listOf(LISTS_BASE + "index.json"), asked)
        assertFalse(File(tmp.root, "x.png").exists())
        // And a logo name from streaming.json can't name a path either.
        assertTrue(parseStreaming("""{"schema": 1, "groups": [{"name": "G", "services": [{"name": "S", "packages": ["a.b"], "color": "#000000", "logo": "../../x"}]}]}""").isEmpty())
    }

    // An oversized PNG, and a 20000 × 20000 one

    @Test fun anOversizedPngIsNeitherCachedNorDecoded() = runTest {
        val big = png(100, 100, size = Png.MAX_BYTES + 1)
        val dir = tmp.newFolder()
        val lists = DeviceLists(dir, served(mapOf("index.json" to index(logos = mapOf("big" to ("logos/big.png" to big))), "logos/big.png" to big)))
        lists.index()
        assertNull(lists.logo("big"))
        assertFalse(File(dir, "logos/big.png").exists())
        assertNull(Png.decode(big))
    }

    @Test fun aDecompressionBombIsRefusedFromItsHeader() = runTest {
        val bomb = png(20000, 20000)
        assertEquals(20000 to 20000, Png.size(bomb))
        assertFalse(Png.ok(bomb))
        assertNull(Png.decode(bomb))
        val dir = tmp.newFolder()
        val lists = DeviceLists(dir, served(mapOf("index.json" to index(logos = mapOf("bomb" to ("logos/bomb.png" to bomb))), "logos/bomb.png" to bomb)))
        lists.index()
        assertNull(lists.logo("bomb"))
        assertFalse(File(dir, "logos/bomb.png").exists())
    }

    @Test fun somethingElseCalledPngIsRefused() {
        assertFalse(Png.ok("<svg onload=alert(1)>".toByteArray()))
        assertFalse(Png.ok(png(0, 10)))
        assertFalse(Png.ok(png(-1, 10)))
        assertTrue(Png.ok(png(278, 76)))
    }

    // A label with an RTL override

    @Test fun controlAndBidiCharactersAreStrippedAndNamesCapped() {
        val rlo = "\u202Egnp.exe"
        assertEquals("gnp.exe", parseLabels("""{"schema": 1, "groups": [{"labels": {"com.acme.app": "$rlo"}}]}""")["com.acme.app"])
        val l = parseLaunchers("""{"schema": 1, "launchers": [{"package": "com.acme.home", "name": "Acme\u2066\u0007 Home\u2028", "about": "${"a".repeat(1000)}"}]}""").single()
        assertEquals("Acme Home", l.name)
        assertEquals(300, l.about!!.length)
        val s = parseStreaming("""{"schema": 1, "groups": [{"name": "\u200FG", "services": [{"name": "${"N".repeat(200)}", "packages": ["a.b"], "color": "#000000", "logo": "n"}]}]}""").single()
        assertEquals(80, s.name.length)
        assertEquals("G", s.group)
        // A name that is nothing but controls is no name.
        assertTrue(parseLaunchers("""{"schema": 1, "launchers": [{"package": "com.acme.home", "name": "\u202E\u202C"}]}""").isEmpty())
    }

    @Test fun coloursAreStrictHex() {
        fun service(color: String) = parseStreaming("""{"schema": 1, "groups": [{"name": "G", "services": [{"name": "S", "packages": ["a.b"], "color": "$color", "logo": "s"}]}]}""")
        assertEquals(1, service("#E50914").size)
        for (bad in listOf("red", "#E5091", "#E50914FF", "#E50914;", "E50914", "#GGGGGG")) assertTrue(bad, service(bad).isEmpty())
    }

    @Test fun anIndexIdThatCouldSplitThePicksFileIsDropped() {
        val sha = "0".repeat(64)
        val idx = parseIndex("""{"schema": 1, "source": {"commit": "x\u202Eevil"}, "devices": [{"id": "a\tb\nc", "name": "X", "file": "devices/x.json", "sha256": "$sha"}]}""")!!
        assertTrue(idx.devices.isEmpty())
        assertNull(idx.commit)
    }

    // An http:// source

    @Test fun anHttpSourceIsRefused() = runTest {
        val lists = DeviceLists(tmp.newFolder(), served(emptyMap()))
        assertEquals(SourceResult.NotHttps, lists.setSource("http://example.com/lists/"))
        assertEquals(LISTS_BASE, lists.base)
        assertTrue(asked.isEmpty())
    }

    @Test fun anHttpSourceStoredOnDiskIsNotUsed() {
        val dir = tmp.newFolder()
        File(dir, "source.txt").writeText("http://example.com/lists/")
        assertEquals(LISTS_BASE, DeviceLists(dir).base)
    }

    @Test fun theDownloaderRefusesAnythingButHttps() = runTest {
        for (url in listOf("http://example.com/index.json", "file:///etc/hosts", "jar:https://x/!/y", "ftp://x/y")) {
            try {
                download(url)
                fail(url)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // A sha mismatch

    @Test fun aListOrLogoWithTheWrongShaIsNeitherCachedNorUsed() = runTest {
        val dir = tmp.newFolder()
        val launchers = """{"schema": 1, "launchers": [{"package": "com.acme.home", "name": "Acme"}]}""".toByteArray()
        val logo = png(10, 10)
        val idx = index(lists = mapOf("launchers" to ("lists/launchers.json" to launchers)), logos = mapOf("acme" to ("logos/acme.png" to logo)))
        val swapped = """{"schema": 1, "launchers": [{"package": "com.evil.home", "name": "Evil"}]}""".toByteArray()
        val lists = DeviceLists(dir, served(mapOf("index.json" to idx, "lists/launchers.json" to swapped, "logos/acme.png" to png(11, 10))))
        assertNull(lists.shared().launchers)
        assertNull(lists.logo("acme"))
        assertFalse(File(dir, "lists/launchers.json").exists())
        assertFalse(File(dir, "logos/acme.png").exists())
    }

    @Test fun aCachedListThatNoLongerMatchesTheIndexIsNotRead() = runTest {
        val dir = tmp.newFolder()
        val launchers = """{"schema": 1, "launchers": [{"package": "com.acme.home", "name": "Acme"}]}""".toByteArray()
        val lists = DeviceLists(dir, served(mapOf("index.json" to index(lists = mapOf("launchers" to ("lists/launchers.json" to launchers))), "lists/launchers.json" to launchers)))
        assertEquals(launchers.decodeToString(), lists.shared().launchers)
        File(dir, "lists/launchers.json").writeText("""{"schema": 1, "launchers": [{"package": "com.evil.home", "name": "Evil"}]}""")
        assertNull(DeviceLists(dir) { throw IOException("offline") }.cachedShared().launchers)
    }

    // A launcher entry asking for accessibility

    @Test fun aLauncherEntryCannotAskForAccessibility() = runTest {
        val l = parseLaunchers("""{"schema": 1, "launchers": [{"package": "com.evil.home", "name": "Evil", "about": "x", "accessibility": true, "service": "com.evil.home/.Spy"}]}""").single()
        assertFalse(l.accessibility)
        assertFalse(actions.grant("com.evil.home/.Spy"))
        assertEquals(emptyList<String>(), ran)
    }
}
