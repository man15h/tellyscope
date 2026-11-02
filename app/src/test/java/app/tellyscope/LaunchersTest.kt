// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.Protected
import app.tellyscope.adb.ProtectedReason
import app.tellyscope.data.ACCESSIBILITY_LAUNCHERS
import app.tellyscope.data.DeviceLists
import app.tellyscope.data.LISTS_BASE
import app.tellyscope.data.KnownLauncher
import app.tellyscope.data.SharedLists
import app.tellyscope.data.parseLabels
import app.tellyscope.data.parseLaunchers
import app.tellyscope.data.parseProtected
import app.tellyscope.ui.RECOMMENDED
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    /** Named here on purpose, so a reviewer sees any launcher that gets to turn on an accessibility service. */
    @Test fun onlyTheBuiltInLaunchersMayTurnOnAnAccessibilityService() {
        assertEquals(setOf("com.spocky.projengmenu", "com.overdevs.at4k", "com.spauldhaliwal.dispatch"), ACCESSIBILITY_LAUNCHERS.keys)
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

    /** A list from someone's fork can't grant an accessibility service: only [ACCESSIBILITY_LAUNCHERS] can. */
    @Test fun badEntriesAreDroppedAndOnlySchemaOneCounts() {
        val text = """{"schema":1,"launchers":[
            {"package":"com.good.launcher","name":"Good","accessibility":true},
            {"package":"not a package; rm -rf /","name":"Bad","accessibility":true},
            {"name":"No package"},
            "not an object"
        ]}"""
        assertEquals(listOf(KnownLauncher("com.good.launcher", "Good")), parseLaunchers(text))
        assertTrue(parseLaunchers(text).none { it.accessibility })
        assertTrue(parseLaunchers(text.replace("\"schema\":1", "\"schema\":2")).isEmpty())
        assertTrue(parseLaunchers("{").isEmpty())
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

    /** The source's `protected.json` reaches [Protected] on top of the built-in list. */
    @Test fun theProtectedListAddsToTheBuiltInOne() = runTest {
        val file = """{"schema":1,"groups":[{"name":"Philips","packages":{"org.droidtv.eum":"tv_inputs_remote","com.android.settings":"updates"}}]}""".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
        val index = """{"schema":1,"devices":[],"lists":{"protected":{"file":"lists/protected.json","sha256":"$sha"}}}""".toByteArray()
        val served = mapOf("index.json" to index, "lists/protected.json" to file)
        try {
            SharedLists.load(DeviceLists(tmp.newFolder()) { url -> served[url.removePrefix(LISTS_BASE)] ?: throw IOException("404 $url") }, fresh = true)
            assertEquals(ProtectedReason.TV_INPUTS_REMOTE, Protected.reason("org.droidtv.eum"))
            assertEquals(ProtectedReason.SETTINGS, Protected.reason("com.android.settings"))
        } finally {
            // Back to the seed lists, for the tests after this one.
            SharedLists.load(DeviceLists(tmp.newFolder()) { throw IOException("offline") }, fresh = true)
        }
    }
}
