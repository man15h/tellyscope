// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.ACCESSIBILITY_LAUNCHERS
import app.tellyscope.data.KnownLauncher
import app.tellyscope.data.SharedLists
import app.tellyscope.data.parseLabels
import app.tellyscope.data.parseLaunchers
import app.tellyscope.ui.RECOMMENDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** org.json is Android's, so this runs under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaunchersTest {
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
}
