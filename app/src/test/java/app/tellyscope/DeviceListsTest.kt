// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.AppInfo
import app.tellyscope.data.DeviceList
import app.tellyscope.data.DeviceLists
import app.tellyscope.data.LISTS_BASE
import app.tellyscope.data.ListEntry
import app.tellyscope.data.ListGroup
import app.tellyscope.data.ListIndex
import app.tellyscope.data.ListMatch
import app.tellyscope.data.ListPackage
import app.tellyscope.data.UiText
import app.tellyscope.data.matches
import app.tellyscope.data.merge
import app.tellyscope.data.SourceResult
import app.tellyscope.data.normalize
import app.tellyscope.data.sourceName
import app.tellyscope.data.depth
import app.tellyscope.data.parseIndex
import app.tellyscope.data.parseList
import app.tellyscope.data.suggest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.MessageDigest

/** org.json is Android's, so this runs under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceListsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val device = """
        {"schema": 1, "id": "acme-55", "name": "Acme 55", "tested": {"os": "Android TV 11", "sdk": 30, "build": null},
         "launcher": null, "keep": [{"pkg": "com.acme.hdmi"}],
         "groups": [{"title": "Junk", "packages": [
           {"pkg": "com.acme.reporter", "note": "usage reporter"},
           {"pkg": "com.acme.updater", "optional": true},
           {"pkg": "com.acme.gone"},
           {"pkg": "com.acme.bad;reboot"}]}],
         "source": {"repo": "Gathin23/cleanyourtv", "path": "examples/acme-55/debloat.txt", "commit": "abc"}}
    """.trimIndent().toByteArray()

    private fun index(sha: String = sha256(device), schema: Int = 1) = """
        {"schema": $schema, "source": {"repo": "Gathin23/cleanyourtv", "commit": "30626a8e4714"},
         "devices": [
           {"id": "acme-55", "name": "Acme 55", "match": {"manufacturer": ["Acme"], "model": ["A55", "A55X"]},
            "sdk": 30, "file": "devices/acme-55.json", "sha256": "$sha"},
           {"id": "other", "name": "Other TV", "match": null, "sdk": null, "file": "devices/other.json", "sha256": "${"0".repeat(64)}"}]}
    """.trimIndent().toByteArray()

    private fun app(pkg: String, enabled: Boolean = true, protectedReason: UiText? = null) =
        AppInfo(pkg, pkg.substringAfterLast('.'), null, true, enabled, protectedReason, "")

    private fun served(vararg files: Pair<String, ByteArray>): suspend (String) -> ByteArray {
        val map = files.associate { (path, bytes) -> LISTS_BASE + path to bytes }
        return { url -> map[url] ?: throw IOException("404 $url") }
    }

    @Test fun everyKeyInTheRuleMustMatchOneOfItsValues() {
        val rule = mapOf("manufacturer" to listOf("Acme"), "model" to listOf("A55", "A55X"))
        assertTrue(matches(rule, mapOf("manufacturer" to "ACME", "model" to "a55x", "device" to "whatever")))
        assertFalse(matches(rule, mapOf("manufacturer" to "Acme", "model" to "A65")))
        assertFalse(matches(rule, mapOf("manufacturer" to "Acme"))) // model unknown
        assertFalse(matches(rule + ("device" to listOf("acmeone")), mapOf("manufacturer" to "Acme", "model" to "A55", "device" to "acmetwo")))
        assertFalse(matches(null, mapOf("manufacturer" to "Acme")))
        assertFalse(matches(emptyMap(), mapOf("manufacturer" to "Acme")))
    }

    @Test fun onlySchemaOneIsAccepted() {
        assertNotNull(parseIndex(index().decodeToString()))
        assertNull(parseIndex(index(schema = 2).decodeToString()))
        assertNull(parseIndex("not json"))
    }

    @Test fun aDeviceFileWithTheWrongSha256IsRejectedAndNotCached() = runTest {
        val lists = DeviceLists(tmp.root, served("index.json" to index(sha = "f".repeat(64)), "devices/acme-55.json" to device))
        val entry = lists.index()!!.devices.first()
        assertNull(lists.list(entry))
        assertFalse(tmp.root.resolve("devices/acme-55.json").exists())

        val good = DeviceLists(tmp.newFolder(), served("index.json" to index(), "devices/acme-55.json" to device))
        assertEquals("Acme 55", good.list(good.index()!!.devices.first())?.name)
    }

    @Test fun offlineFallsBackToTheCachedIndexAndLists() = runTest {
        DeviceLists(tmp.root, served("index.json" to index(), "devices/acme-55.json" to device)).let {
            it.list(it.index()!!.devices.first())
        }
        val offline = DeviceLists(tmp.root) { throw IOException("offline") }
        val index = offline.index()
        assertEquals("30626a8e4714", index?.commit)
        assertNotNull(offline.list(index!!.devices.first()))
        assertNull(DeviceLists(tmp.newFolder()) { throw IOException("offline") }.index())
    }

    @Test fun aPickIsRememberedPerTvAndBeatsAMatch() = runTest {
        val index = parseIndex(index().decodeToString())!!
        val props = mapOf("manufacturer" to "Acme", "model" to "A55")
        DeviceLists(tmp.root).pick("sn:one", "other")
        val lists = DeviceLists(tmp.root)
        assertEquals("other", (lists.find(index, "sn:one", props) as ListMatch.Model).entry.id)
        assertEquals("acme-55", (lists.find(index, "sn:two", props) as ListMatch.Model).entry.id)
        assertEquals(ListMatch.None, lists.find(index, "sn:two", mapOf("manufacturer" to "Zeta", "model" to "Z1")))
    }

    private fun entry(id: String, name: String) = ListEntry(id, name, null, 30, "devices/$id.json", "0".repeat(64))

    private val fakes = ListIndex(
        null,
        listOf(
            entry("xgimi-mogo-2", "XGIMI MoGo 2"),
            entry("xgimi-horizon", "XGIMI Horizon"),
            entry("tcl-p7-2021", "TCL P7 2021"),
            entry("tcl-p7-2023", "TCL P7 2023"),
            entry("tcl-x100", "TCL X100"),
        ),
    )

    private fun find(manufacturer: String, model: String, brand: String? = null) =
        DeviceLists(tmp.root).find(fakes, "sn:x", mapOf("manufacturer" to manufacturer, "model" to model, "brand" to brand))

    @Test fun oneListNamedAfterTheModelIsTheTvsList() {
        // Case, spaces and dashes don't count.
        assertEquals(ListMatch.Model(fakes.devices[0]), find("Xgimi", "MOGO-2"))
    }

    @Test fun twoListsForTheModelFallBackToTheMaker() {
        val m = find("TCL", "P7") as ListMatch.Maker
        assertEquals("TCL", m.maker)
        assertEquals(listOf("tcl-p7-2021", "tcl-p7-2023", "tcl-x100"), m.entries.map { it.id })
    }

    @Test fun theModelMustBeWholeWordsNotPartOfOne() {
        // "X10" is not "X100": the maker's lists, unticked, instead of the wrong model's.
        assertTrue(find("TCL", "X10") is ListMatch.Maker)
        assertEquals(ListMatch.Model(fakes.devices[4]), find("TCL", "X100"))
    }

    @Test fun theBrandFindsTheMakerWhenTheManufacturerDoesnt() {
        // Like a Philips TV, which reports its factory, TPV, as manufacturer.
        assertEquals(ListMatch.Model(fakes.devices[0]), find("Zeta", "MoGo 2", brand = "XGIMI"))
        assertEquals(2, (find("Zeta", "Halo+", brand = "XGIMI") as ListMatch.Maker).entries.size)
    }

    @Test fun aModelWithNoListGetsEveryListFromItsMaker() {
        val m = find("XGIMI", "Halo+") as ListMatch.Maker
        assertEquals(listOf("xgimi-mogo-2", "xgimi-horizon"), m.entries.map { it.id })
        // A list named after the model, but from another maker, is not this TV's.
        assertEquals(ListMatch.None, find("Zeta", "MoGo 2"))
    }

    @Test fun noListForTheModelOrMakerIsNone() {
        assertEquals(ListMatch.None, find("Zeta", "Z1"))
        assertEquals(ListMatch.None, find("", ""))
    }

    @Test fun aKeepInOneOfTheMakersListsProtectsAnAppAnotherTurnsOff() {
        val a = DeviceList("tcl-p7-2021", "TCL P7 2021", "Android TV 11", 30, setOf("com.tcl.hdmi"), listOf(ListGroup("Junk", listOf(ListPackage("com.tcl.ads", null, false)))))
        val b = DeviceList(
            "tcl-p7-2023", "TCL P7 2023", "Android TV 12", 31, emptySet(),
            listOf(ListGroup("Junk", listOf(ListPackage("com.tcl.hdmi", null, false), ListPackage("com.tcl.ads", null, false)))),
        )
        val merged = merge("TCL", listOf(a, b))
        assertEquals(listOf("com.tcl.ads", "com.tcl.hdmi"), merged.groups.single().packages.map { it.pkg })
        val shown = suggest(merged, listOf(app("com.tcl.hdmi"), app("com.tcl.ads")), sdk = 30).flatMap { it.packages }
        assertEquals(listOf("com.tcl.ads"), shown.map { it.pkg })
        // No tested Android, so nothing starts ticked.
        assertFalse(shown.single().ticked)
    }

    @Test fun onlyPackagesTheTvHasAndStillHasOnAreSuggested() = runTest {
        val lists = DeviceLists(tmp.root, served("index.json" to index(), "devices/acme-55.json" to device))
        val list = lists.list(lists.index()!!.devices.first())!!
        // The shell-metacharacter name never made it past parsing.
        assertEquals(listOf("com.acme.reporter", "com.acme.updater", "com.acme.gone"), list.groups.single().packages.map { it.pkg })

        val apps = listOf(app("com.acme.reporter"), app("com.acme.updater", enabled = false), app("com.netflix.ninja"))
        val shown = suggest(list, apps, sdk = 30).flatMap { it.packages }.map { it.pkg }
        assertEquals(listOf("com.acme.reporter"), shown)
    }

    @Test fun ticksOnlyNonOptionalEntriesAndOnlyOnTheTestedSdk() {
        val list = DeviceList(
            "acme-55", "Acme 55", "Android TV 11", 30, emptySet(),
            listOf(ListGroup("Junk", listOf(ListPackage("com.a.one", null, false), ListPackage("com.a.two", null, true)))),
        )
        val apps = listOf(app("com.a.one"), app("com.a.two"))
        fun ticks(sdk: Int?) = suggest(list, apps, sdk).flatMap { it.packages }.associate { it.pkg to it.ticked }
        assertEquals(mapOf("com.a.one" to true, "com.a.two" to false), ticks(30))
        assertEquals(mapOf("com.a.one" to false, "com.a.two" to false), ticks(31))
        assertEquals(mapOf("com.a.one" to false, "com.a.two" to false), ticks(null))
        assertEquals(mapOf("com.a.one" to false, "com.a.two" to false), suggest(list.copy(sdk = null), apps, null).flatMap { it.packages }.associate { it.pkg to it.ticked })
    }

    @Test fun anAppInTwoGroupsOrTwoGroupsWithOneTitleShowOnce() {
        fun p(pkg: String) = ListPackage(pkg, null, false)
        val list = DeviceList(
            "x", "X", null, 30, emptySet(),
            listOf(
                ListGroup("Junk", listOf(p("com.a.one"), p("com.a.two"))),
                ListGroup("Ads", listOf(p("com.a.one"), p("com.a.three"))),
                ListGroup("Junk", listOf(p("com.a.four"), p("com.a.two"))),
            ),
        )
        val groups = suggest(list, listOf("one", "two", "three", "four").map { app("com.a.$it") }, 30)
        // The screen keys rows by group title and by package, so a repeat of either crashes it.
        assertEquals(listOf("Junk", "Ads"), groups.map { it.title })
        assertEquals(listOf("com.a.one", "com.a.two", "com.a.four"), groups[0].packages.map { it.pkg })
        assertEquals(listOf("com.a.three"), groups[1].packages.map { it.pkg })
    }

    @Test fun oneBadEntryIsDroppedNotTheWholeIndex() {
        val text = """
            {"schema": 1, "devices": [
              {"id": "no-sha", "name": "No sha", "file": "devices/no-sha.json"},
              "not an object",
              {"id": "good", "name": "Good", "file": "devices/good.json", "sha256": "${"0".repeat(64)}"}]}
        """.trimIndent()
        assertEquals(listOf("good"), parseIndex(text)?.devices?.map { it.id })
    }

    @Test fun oneBadPackageOrGroupIsDroppedNotTheWholeList() {
        val text = """
            {"schema": 1, "id": "x", "name": "X", "keep": [{"note": "no pkg"}, {"pkg": "com.a.keep"}],
             "groups": [7, {"title": "Junk", "packages": [{"note": "no pkg"}, {"pkg": "com.a.one"}]}]}
        """.trimIndent()
        val list = parseList(text, "x")
        assertEquals(setOf("com.a.keep"), list?.keep)
        assertEquals(listOf("com.a.one"), list?.groups?.flatMap { g -> g.packages.map { it.pkg } })
    }

    @Test fun keepIsNeverSuggested() {
        val keep = setOf("com.acme.hdmi")
        // A list that names a package in both keep and a group never suggests it.
        val list = DeviceList("x", "X", null, 30, keep, listOf(ListGroup("Junk", listOf(ListPackage("com.acme.hdmi", null, false)))))
        assertTrue(suggest(list, listOf(app("com.acme.hdmi")), 30).isEmpty())
        // Nor one the app already protects.
        val builtIn = list.copy(keep = emptySet())
        assertTrue(suggest(builtIn, listOf(app("com.acme.hdmi", protectedReason = UiText(R.string.data_protected_tv_inputs_remote))), 30).isEmpty())
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    @Test fun deeplyNestedJsonIsRejectedNotACrash() {
        // org.json parses recursively: this used to throw StackOverflowError on every TV page open.
        val bomb = "[".repeat(200_000) + "]".repeat(200_000)
        assertNull(parseList("""{"schema":1,"id":"x","name":"x","groups":$bomb}""", "x"))
        assertNull(parseIndex("""{"schema":1,"devices":$bomb}"""))
        // Brackets inside strings don't count.
        assertEquals(2, depth("""{"a":["[[[{{{\"]"]}"""))
    }

    private val launchers = """{"schema": 1, "launchers": [{"package": "com.good.launcher", "name": "Good"}]}""".toByteArray()
    /** A PNG signature and an IHDR for 1 × 1, which is all [app.tellyscope.data.Png.ok] reads. */
    private val logo = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
        byteArrayOf(0, 0, 0, 13) + "IHDR".toByteArray() + byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0)

    /** An index with only lists and logos; [bad] adds entries whose file is outside lists/ or logos/. */
    private fun sharedIndex(launchersSha: String = sha256(launchers), bad: Boolean = false) = """
        {"schema": 1, "devices": [],
         "lists": {"launchers": {"file": "lists/launchers.json", "sha256": "$launchersSha"}${if (bad) """, "labels": {"file": "../labels.json", "sha256": "${"0".repeat(64)}"}""" else ""}},
         "logos": {"netflix": {"file": "logos/netflix.png", "sha256": "${sha256(logo)}"}${if (bad) """, "x": {"file": "logos/x.svg", "sha256": "${"0".repeat(64)}"}""" else ""}}}
    """.trimIndent().toByteArray()

    @Test fun theIndexNamesListsAndLogosOnlyInsideTheirFolders() {
        val index = parseIndex(sharedIndex(bad = true).decodeToString())!!
        assertEquals(setOf("launchers"), index.lists.keys)
        assertEquals(setOf("netflix"), index.logos.keys)
        // An index from before they moved here still reads.
        assertTrue(parseIndex(index().decodeToString())!!.lists.isEmpty())
    }

    @Test fun listsAndLogosAreCheckedCachedAndReadOffline() = runTest {
        val lists = DeviceLists(tmp.root, served("index.json" to sharedIndex(), "lists/launchers.json" to launchers, "logos/netflix.png" to logo))
        assertEquals(launchers.decodeToString(), lists.shared().launchers)
        assertNull(lists.shared().streaming) // the index names none
        assertTrue(logo.contentEquals(lists.logo("netflix")))
        assertNull(lists.logo("hulu"))

        val offline = DeviceLists(tmp.root) { throw IOException("offline") }
        assertEquals(launchers.decodeToString(), offline.cachedShared().launchers)
        assertEquals(launchers.decodeToString(), offline.shared().launchers)
        assertTrue(logo.contentEquals(offline.logo("netflix")))

        val wrong = DeviceLists(tmp.newFolder(), served("index.json" to sharedIndex(launchersSha = "f".repeat(64)), "lists/launchers.json" to launchers))
        assertNull(wrong.shared().launchers)
    }

    private val fork = "https://raw.githubusercontent.com/someone/my-lists/main/"

    @Test fun aForkThatServesSchemaOneBecomesTheSourceAndDropsTheOldCache() = runTest {
        val served = mapOf(
            LISTS_BASE + "index.json" to index(), LISTS_BASE + "devices/acme-55.json" to device,
            fork + "index.json" to index(sha = "f".repeat(64)),
        )
        val fetched = mutableListOf<String>()
        val fetch: suspend (String) -> ByteArray = { url -> fetched += url; served[url] ?: throw IOException("404 $url") }
        val lists = DeviceLists(tmp.root, fetch)
        assertNotNull(lists.list(lists.index()!!.devices.first()))
        assertTrue(tmp.root.resolve("devices/acme-55.json").exists())
        tmp.root.resolve("logos").mkdirs()
        tmp.root.resolve("logos/netflix.png").writeBytes(logo)

        assertEquals(SourceResult.Ok, lists.setSource(fork.removeSuffix("/") + "/index.json"))
        assertEquals(fork, lists.custom())
        assertFalse(tmp.root.resolve("devices").exists())
        assertFalse(tmp.root.resolve("logos").exists())
        // Survives a restart, and every later fetch goes to the fork.
        val again = DeviceLists(tmp.root, fetch)
        assertEquals(fork, again.base)
        fetched.clear()
        assertNull(again.list(again.index()!!.devices.first())) // the fork's checksum doesn't match
        assertTrue(fetched.isNotEmpty() && fetched.all { it.startsWith(fork) })

        assertEquals(SourceResult.Ok, again.setSource(null))
        assertNull(again.custom())
        assertEquals(LISTS_BASE, again.base)
    }

    @Test fun aSourceThatIsntATellyscopeListChangesNothing() = runTest {
        val lists = DeviceLists(tmp.root, served("index.json" to index()))
        lists.index()
        val other: suspend (String) -> ByteArray = { url ->
            when (url) {
                fork + "index.json" -> """{"schema": 2, "devices": []}""".toByteArray()
                LISTS_BASE + "index.json" -> index()
                else -> throw IOException("404 $url")
            }
        }
        val l = DeviceLists(tmp.root, other)
        assertEquals(SourceResult.NotAList, l.setSource(fork))
        assertEquals(SourceResult.NotHttps, l.setSource("http://example.com/lists/"))
        assertEquals(SourceResult.Unreachable, l.setSource("https://example.com/nothing/"))
        assertNull(l.custom())
        assertTrue(tmp.root.resolve("index.json").exists())
    }

    @Test fun resetToDefaultWorksOffline() = runTest {
        val lists = DeviceLists(tmp.root) { url -> if (url == fork + "index.json") index() else throw IOException("offline") }
        assertEquals(SourceResult.Ok, lists.setSource(fork))
        assertEquals(SourceResult.Ok, lists.setSource(null))
        assertNull(lists.custom())
        assertNull(lists.index()) // nothing from the fork is left to fall back on
    }

    @Test fun sourceAddressesAreHttpsFoldersAndReadShort() {
        assertEquals("https://example.com/lists/", normalize(" https://example.com/lists "))
        assertEquals("https://example.com/lists/", normalize("https://example.com/lists/index.json"))
        assertNull(normalize("http://example.com/lists/"))
        assertNull(normalize("file:///sdcard/lists/"))
        assertNull(normalize("https://example.com/lists/?x=1"))
        assertNull(normalize("https://someone:ghp_secret@raw.githubusercontent.com/someone/my-lists/main/"))
        assertEquals("github.com/someone/my-lists", sourceName(fork))
        assertEquals("github.com/someone/my-lists (test)", sourceName("https://raw.githubusercontent.com/someone/my-lists/test/"))
        assertEquals("github.com/someone/my-lists (main/tv)", sourceName("https://raw.githubusercontent.com/someone/my-lists/main/tv/"))
        assertEquals("example.com/lists", sourceName("https://example.com/lists/"))
    }
}
