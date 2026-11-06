// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.Protected
import app.tellyscope.adb.ProtectedReason
import app.tellyscope.data.DATA_BASE
import app.tellyscope.data.DeviceLists
import app.tellyscope.data.LISTS_BASE
import app.tellyscope.data.KnownLauncher
import app.tellyscope.data.ListFile
import app.tellyscope.data.SharedLists
import app.tellyscope.data.hasServiceFrom
import app.tellyscope.data.parseLabels
import app.tellyscope.data.parseLaunchers
import app.tellyscope.data.parseProtected
import app.tellyscope.ui.RECOMMENDED
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** org.json is Android's, so this runs under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaunchersTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun theListReadsWhole() {
        val text = File("src/test/resources/fixtures/launchers.json").readText()
        val launchers = parseLaunchers(text)
        assertEquals(Regex("\"package\"").findAll(text).count(), launchers.size) // nothing dropped
        assertEquals(launchers.size, launchers.map { it.pkg }.toSet().size)
    }

    /** The real list's are named in [DataListsTest.theLaunchersWithAnAccessibilityServiceAreNamed]. */
    @Test fun aLauncherWithAccessibilityMayTurnItsServiceOn() {
        assertEquals(setOf("com.spocky.projengmenu"), SharedLists.serviceLaunchers)
        assertEquals("4.72", SharedLists.launchers.first { it.pkg == "com.spocky.projengmenu" }.accessibility)
    }

    @Test fun theRecommendedOnesAreTheLaunchersWithAnAboutLineInFileOrder() {
        assertEquals(SharedLists.launchers.filter { it.about != null }.map { it.pkg }, RECOMMENDED.map { it.pkg })
        assertTrue(RECOMMENDED.isNotEmpty()) // the picker opens on the first one
    }

    @Test fun theLabelsReadWhole() {
        val text = File("src/test/resources/fixtures/labels.json").readText()
        val labels = parseLabels(text)
        // Every "key": "text" pair but the group names is a label: none dropped, none listed twice.
        val pairs = Regex("\"[^\"]+\"\\s*:\\s*\"").findAll(text).count()
        assertEquals(pairs - Regex("\"name\"\\s*:").findAll(text).count(), labels.size)
        assertEquals("Android System", labels["android"])
        assertEquals("Netflix", SharedLists.labels["com.netflix.ninja"])
    }

    @Test fun badLabelsAreDroppedAndOnlySchemaOneCounts() {
        val text = """{"schema":1,"groups":[
            {"name":"Good","labels":{"com.good.app":"Good","android":"Android System","not a package":"Bad"}},
            {"name":"No labels"},
            "not an object"
        ]}"""
        assertEquals(mapOf("com.good.app" to "Good", "android" to "Android System"), parseLabels(text))
        assertTrue(parseLabels(text.replace("\"schema\":1", "\"schema\":2")).isEmpty())
        assertTrue(parseLabels("{").isEmpty())
    }

    /** `accessibility` is the version checked, so `true` is no answer; whether it counts is [SharedLists]' call. */
    @Test fun badEntriesAreDroppedAndOnlySchemaOneCounts() {
        val text = """{"schema":1,"launchers":[
            {"package":"com.good.launcher","name":"Good","accessibility":true},
            {"package":"not a package; rm -rf /","name":"Bad","accessibility":true},
            {"name":"No package"},
            "not an object"
        ]}"""
        assertEquals(listOf(KnownLauncher("com.good.launcher", "Good")), parseLaunchers(text))
        assertTrue(parseLaunchers(text).none { it.accessibility != null })
        assertTrue(parseLaunchers(text.replace("\"schema\":1", "\"schema\":2")).isEmpty())
        assertTrue(parseLaunchers("{").isEmpty())
    }

    @Test fun iconsAndScreenshotsAreFilesInDataImages() {
        fun one(fields: String) = parseLaunchers("""{"schema": 1, "launchers": [{"package": "a.b", "name": "A", $fields}]}""").single()
        val sha = "a".repeat(64)
        fun shot(file: String, hash: String = sha) = """"screenshot": {"file": "$file", "sha256": "$hash"}"""
        fun icon(file: String, hash: String = sha) = """"icon": {"file": "$file", "sha256": "$hash"}"""
        val l = one("""${icon("images/flauncher-icon.png")}, ${shot("images/flauncher.png")}, "credit": "Screenshot from A's Play Store page"""")
        assertEquals(
            Triple(ListFile("images/flauncher-icon.png", sha), ListFile("images/flauncher.png", sha), "Screenshot from A's Play Store page"),
            Triple(l.icon, l.screenshot, l.credit),
        )
        // An address on another host, as lists before had: no icon, so the phone asks no one but data/.
        assertEquals(KnownLauncher("a.b", "A"), one(""""icon": "https://example.com/i.png""""))
        assertNull(one(icon("images/../index.json")).icon)
        assertNull(one(icon("images/flauncher-icon.png", "not a hash")).icon)
        assertNull(one(shot("https://example.com/s.png")).screenshot)
        assertNull(one(shot("images/../index.json")).screenshot)
        assertNull(one(shot("images/Monet.png")).screenshot)
        assertNull(one(shot("logos/netflix.png")).screenshot)
        assertNull(one(shot("images/monet.png", "not a hash")).screenshot)
        // The bare name an earlier draft used: no picture, and the launcher is still read.
        assertEquals(KnownLauncher("a.b", "A"), one(""""screenshot": "monet""""))
        assertNull(one(""""credit": "  """").credit)
    }

    /** An app that predates screenshots reads the same launchers from today's list, as only the keys it knows count. */
    @Test fun aScreenshotObjectDoesNotDropTheLauncher() {
        val text = """{"schema": 1, "launchers": [{"package": "a.b", "name": "A", "accessibility": "1.0",
            "screenshot": {"file": "images/a.png", "sha256": "${"a".repeat(64)}"}, "credit": "Screenshot from A's page"}]}"""
        val l = parseLaunchers(text).single()
        assertEquals(KnownLauncher("a.b", "A", accessibility = "1.0"), l.copy(screenshot = null, credit = null))
    }

    /** A list can't claim the home screen or the TV's own states, and a reason this app doesn't know still protects. */
    @Test fun badProtectedEntriesAreDroppedAndOnlySchemaOneCounts() {
        val text = """{"schema":1,"groups":[
            {"name":"Acme","packages":{"com.acme.help":"tv_inputs_remote","com.acme.home":"home_screen","com.acme.new":"some_new_reason","not a package":"system"}},
            {"name":"No packages"},
            "not an object"
        ]}"""
        assertEquals(
            mapOf("com.acme.help" to ProtectedReason.TV_INPUTS_REMOTE, "com.acme.home" to ProtectedReason.SYSTEM, "com.acme.new" to ProtectedReason.SYSTEM),
            parseProtected(text),
        )
        assertTrue(parseProtected(text.replace("\"schema\":1", "\"schema\":2")).isEmpty())
        assertTrue(parseProtected("{").isEmpty())
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The source's `protected.json` reaches [Protected] on top of the built-in list. */
    @Test fun theProtectedListAddsToTheBuiltInOne() = runTest {
        val file = """{"schema":1,"groups":[{"name":"Philips","packages":{"org.droidtv.eum":"tv_inputs_remote","com.android.settings":"updates"}}]}""".toByteArray()
        val index = """{"schema":1,"devices":[],"lists":{"protected":{"file":"lists/protected.json","sha256":"${sha(file)}"}}}""".toByteArray()
        val served = mapOf("index.json" to index, "lists/protected.json" to file)
        try {
            SharedLists.load(DeviceLists(tmp.newFolder(), DATA_BASE) { throw IOException("offline") }, DeviceLists(tmp.newFolder()) { url -> served[url.removePrefix(LISTS_BASE)] ?: throw IOException("404 $url") }, fresh = true)
            assertEquals(ProtectedReason.TV_INPUTS_REMOTE, Protected.reason("org.droidtv.eum"))
            assertEquals(ProtectedReason.SETTINGS, Protected.reason("com.android.settings"))
        } finally {
            SharedLists.reseed() // the seed lists, for the tests after this one
        }
    }

    /** Launchers come from `data/` only: a list source that names its own, like a fork's, isn't read for them. */
    @Test fun launchersComeFromDataNotTheListSource() = runTest {
        val theirs = """{"schema":1,"launchers":[{"package":"com.evil.home","name":"Evil","about":"x"}]}""".toByteArray()
        val ours = """{"schema":1,"launchers":[{"package":"com.good.home","name":"Good","about":"x"}]}""".toByteArray()
        fun index(f: ByteArray, file: String) = """{"schema":1,"devices":[],"lists":{"launchers":{"file":"$file","sha256":"${sha(f)}"}}}""".toByteArray()
        val asked = mutableListOf<String>()
        val source = mapOf("index.json" to index(theirs, "lists/launchers.json"), "lists/launchers.json" to theirs)
        val data = mapOf("index.json" to index(ours, "launchers.json"), "launchers.json" to ours)
        try {
            SharedLists.load(
                DeviceLists(tmp.newFolder(), DATA_BASE) { url -> data[url.removePrefix(DATA_BASE)] ?: throw IOException("404 $url") },
                DeviceLists(tmp.newFolder()) { url -> asked += url; source[url.removePrefix(LISTS_BASE)] ?: throw IOException("404 $url") },
                fresh = true,
            )
            assertEquals(listOf("com.good.home"), SharedLists.launchers.map { it.pkg })
            assertEquals(listOf(LISTS_BASE + "index.json"), asked)
        } finally {
            SharedLists.reseed() // the seed lists, for the tests after this one
        }
    }

    /** `"accessibility"` is the minimum version that has the service: equal and newer pass, older and junk don't. */
    @Test fun theServiceVersionIsAMinimum() {
        assertTrue(hasServiceFrom("4.72", "4.72"))
        assertTrue(hasServiceFrom("4.72", "4.72.0"))
        assertTrue(hasServiceFrom("4.72", "4.80"))
        assertTrue(hasServiceFrom("4.72", "5.0"))
        assertTrue(hasServiceFrom("1.0.175", "1.0.200-beta"))
        assertFalse(hasServiceFrom("4.72", "4.71"))
        assertFalse(hasServiceFrom("4.72", "4.7"))
        assertFalse(hasServiceFrom("1.0.175", "1.0.99"))
        assertFalse(hasServiceFrom("4.72", null))
        assertFalse(hasServiceFrom("4.72", ""))
        assertFalse(hasServiceFrom("4.72", "beta-4.72"))
        assertFalse(hasServiceFrom("4.72", "99999999999999999999"))
        assertFalse(hasServiceFrom("x", "4.72"))
    }
}
