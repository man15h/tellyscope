// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.adb.AppLabel
import app.tellyscope.adb.Parsers
import app.tellyscope.data.AppNames
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The fallback names come from the test fixture `labels.json`, read with Android's org.json: Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNamesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val icon = byteArrayOf(1, 2, 3)
    private val netflix = Parsers.Package("com.netflix.ninja", null, 10)
    private val odd = Parsers.Package("com.example.weatherbox", null, 3)

    /** Answers like the helper would, from the TV's current versions, and records each run. */
    private class Helper(val fail: Boolean = false) {
        val runs = mutableListOf<List<String>>()
        var versions = mutableMapOf<String, Long>()
        suspend fun describe(packages: List<String>): List<AppLabel>? {
            runs += packages
            if (fail) return null
            return packages.map { AppLabel(it, "Real ${it.substringAfterLast('.')}", versions[it] ?: 0, byteArrayOf(1, 2, 3)) }
        }
    }

    @Test fun helperNamesAndIcons() = runBlocking {
        val helper = Helper()
        val names = AppNames(tmp.root).names("SN1", listOf(netflix, odd), helper::describe)
        assertEquals("Real ninja", names.getValue("com.netflix.ninja").label)
        assertArrayEquals(icon, names.getValue("com.netflix.ninja").icon)
    }

    @Test fun aTvsCacheIsReadOnceAndKept() = runBlocking {
        val helper = Helper().apply { versions = mutableMapOf(netflix.name to 10, odd.name to 3) }
        val appNames = AppNames(tmp.root)
        val first = appNames.names("SN1", listOf(netflix, odd), helper::describe)
        // Gone from disk: the next list still comes from memory, with the very same icon.
        tmp.root.listFiles()!!.forEach { it.delete() }
        val again = appNames.names("SN1", listOf(netflix, odd), helper::describe)
        assertEquals(1, helper.runs.size)
        assertSame(first.getValue(netflix.name).icon, again.getValue(netflix.name).icon)
    }

    @Test fun fallbackKeepsTheMaker() {
        assertEquals("XGIMI Assistant", AppNames.fallback("com.xgimi.assistant"))
        assertEquals("Youtube", AppNames.fallback("com.google.android.youtube"))
        assertEquals("Spotifytv", AppNames.fallback("com.spotify.spotifytv")) // maker already in the name
    }

    @Test fun aClassNameInPlaceOfALabelIsHumanised() {
        assertEquals("Amazon Firehomestarter", AppNames.named("com.amazon.firehomestarter", "com.amazon.firehomestarter.FireHomeStarterApplication"))
        assertEquals("Example Weatherbox", AppNames.named("com.example.weatherbox", "com.example.weatherbox"))
        assertEquals("Fire TV Home", AppNames.named("com.amazon.tv.launcher", "Fire TV Home"))
    }

    @Test fun fallbackWhenTheHelperFails() = runBlocking {
        val helper = Helper(fail = true)
        val appNames = AppNames(tmp.root)
        val names = appNames.names("SN1", listOf(netflix, odd), helper::describe)
        assertEquals("Netflix", names.getValue("com.netflix.ninja").label) // fixture table
        assertEquals("Example Weatherbox", names.getValue("com.example.weatherbox").label) // humanised id, maker kept
        assertNull(names.getValue("com.netflix.ninja").icon) // letter avatar
        // A refusal isn't retried on every open; the next app start tries again.
        appNames.names("SN1", listOf(netflix, odd), helper::describe)
        assertEquals(1, helper.runs.size)
    }

    @Test fun helperRunsAgainOnlyForNewOrUpdatedApps() = runBlocking {
        val helper = Helper().apply { versions = mutableMapOf(netflix.name to 10, odd.name to 3) }
        AppNames(tmp.root).names("SN1", listOf(netflix, odd), helper::describe)
        // A fresh instance reads the cache from disk, as after an app restart.
        val later = AppNames(tmp.root)
        later.names("SN1", listOf(netflix, odd), helper::describe)
        assertEquals(1, helper.runs.size)

        val youtube = Parsers.Package("com.google.android.youtube.tv", null, 5)
        helper.versions[netflix.name] = 11
        later.names("SN1", listOf(netflix.copy(versionCode = 11), odd, youtube), helper::describe)
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv"), helper.runs.last())
    }

    @Test fun anIconlessEntryIsAskedForOnceMorePerLaunch() = runBlocking {
        // One run lost Netflix's icon; the cache kept the label without it.
        AppNames(tmp.root).names("SN1", listOf(netflix, odd), { listOf(AppLabel(netflix.name, "Netflix", 10, null), AppLabel(odd.name, "Weather", 3, icon)) })
        val helper = Helper().apply { versions = mutableMapOf(netflix.name to 10) }
        val later = AppNames(tmp.root)
        val names = later.names("SN1", listOf(netflix, odd), helper::describe)
        assertEquals(listOf(listOf(netflix.name)), helper.runs)
        assertArrayEquals(icon, names.getValue(netflix.name).icon)
        // Asked once this launch: not again, even if that run had also come back without one.
        later.names("SN1", listOf(netflix, odd), helper::describe)
        assertEquals(1, helper.runs.size)
    }

    @Test fun cacheIsPerTv() = runBlocking {
        val helper = Helper()
        val appNames = AppNames(tmp.root)
        appNames.names("SN1", listOf(netflix), helper::describe)
        appNames.names("SN2", listOf(netflix), helper::describe)
        assertEquals(2, helper.runs.size)
    }

    @Test fun appsTheHelperSkipsArentAskedForAgain() = runBlocking {
        var runs = 0
        val appNames = AppNames(tmp.root)
        repeat(2) {
            appNames.names("SN1", listOf(netflix, odd), { runs++; listOf(AppLabel("com.netflix.ninja", "Netflix", 10, null)) })
        }
        assertEquals(1, runs)
    }

    @Test fun damagedCacheLinesCostOnlyThatApp() = runBlocking {
        File(tmp.root, "SN1.v2.tsv").writeText("com.netflix.ninja\t10\tNetflix\tAQID\t\t\ngarbage line\ncom.example.weatherbox\tx\tW\t\t\t\n")
        val helper = Helper()
        val names = AppNames(tmp.root).names("SN1", listOf(netflix, odd), helper::describe)
        assertEquals("Netflix", names.getValue("com.netflix.ninja").label)
        assertEquals(listOf("com.example.weatherbox"), helper.runs.single())
    }

    @Test fun aBannerAppsTileColoursAreCached() = runBlocking {
        val tile = AppLabel(odd.name, "Weather", 3, null, 0xFF101010, 0xFF1ED760)
        val helper = Helper()
        AppNames(tmp.root).names("SN1", listOf(odd), { listOf(tile) })
        // A new launch reads the cache, and a tile counts as having its icon: no second run.
        val names = AppNames(tmp.root).names("SN1", listOf(odd), helper::describe)
        assertEquals(0xFF101010, names.getValue(odd.name).tileColor)
        assertEquals(0xFF1ED760, names.getValue(odd.name).tileAccent)
        assertEquals(0, helper.runs.size)
    }

    @Test fun cacheFromBeforeAdaptiveIconsIsReadAgainAndDropped() = runBlocking {
        // Version 1 held a banner where an adaptive icon now loads, at the same versionCode.
        val old = File(tmp.root, "SN1.tsv").apply { writeText("com.example.weatherbox\t3\tWeather\tAQID\t\t\n") }
        val helper = Helper()
        AppNames(tmp.root).names("SN1", listOf(odd), helper::describe)
        assertEquals(listOf("com.example.weatherbox"), helper.runs.single())
        assertFalse(old.exists())
    }

    @Test fun cacheFromBeforeTilesIsReadAgain() = runBlocking {
        // The old four-column line held a squashed banner; it must not survive the update.
        File(tmp.root, "SN1.tsv").writeText("com.example.weatherbox\t3\tWeather\tAQID\n")
        val helper = Helper()
        AppNames(tmp.root).names("SN1", listOf(odd), helper::describe)
        assertEquals(listOf("com.example.weatherbox"), helper.runs.single())
    }
}
