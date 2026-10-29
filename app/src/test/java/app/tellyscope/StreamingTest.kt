// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.AppInfo
import app.tellyscope.data.Docked
import app.tellyscope.data.RemoteButtons
import app.tellyscope.data.SharedLists
import app.tellyscope.data.StreamingService
import app.tellyscope.data.parseStreaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** org.json is Android's, so this runs under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamingTest {
    private val services = SharedLists.streaming

    @Test fun theListReadsWhole() {
        val text = File("src/test/resources/fixtures/streaming.json").readText()
        val parsed = parseStreaming(text)
        assertEquals(Regex("\"packages\"").findAll(text).count(), parsed.size) // nothing dropped
        assertEquals(parsed.size, parsed.map { it.name }.toSet().size)
        val pkgs = parsed.flatMap { it.packages }
        assertEquals(pkgs.size, pkgs.toSet().size) // no package belongs to two services
    }

    @Test fun aServiceWithoutAColourOrAPackageIsDropped() {
        val text = """{ "schema": 1, "groups": [ { "name": "G", "services": [
            { "name": "A", "packages": ["com.a"], "color": "#112233", "logo": "a" },
            { "name": "B", "packages": ["com.b"], "color": "red", "logo": "b" },
            { "name": "C", "packages": ["not a package"], "color": "#112233", "logo": "c" },
            { "name": "D", "packages": ["com.d"], "color": "#112233", "logo": "../d" }
        ] } ] }"""
        assertEquals(listOf("A"), parseStreaming(text).map { it.name })
        assertEquals(0xFF112233, parseStreaming(text).single().color)
        assertEquals(emptyList<StreamingService>(), parseStreaming(text.replace("\"schema\": 1", "\"schema\": 2")))
    }

    private fun service(name: String) = services.first { it.name == name }

    @Test fun aPhoneShowsThreeAndAWideScreenFour() {
        assertEquals(3, RemoteButtons.cap(411))
        assertEquals(4, RemoteButtons.cap(600))
        val dock = listOf("Netflix", "YouTube", "Plex", "Kodi").map { Docked(service(it).packages.first(), it) }
        assertEquals(listOf("Netflix", "YouTube", "Plex"), RemoteButtons.shown(dock, services, 3).map { it.name })
        assertEquals(4, RemoteButtons.shown(dock, services, 4).size)
        // An app docked before the buttons, that isn't a service, doesn't show.
        assertEquals(listOf("Netflix"), RemoteButtons.shown(listOf(Docked("com.example.app", "App"), dock[0]), services, 3).map { it.name })
    }

    @Test fun aTickAddsInOrderUpToTheCapAndAnUntickRemoves() {
        var dock = emptyList<Docked>()
        listOf("Netflix", "Plex", "Kodi", "Emby").forEach { dock = RemoteButtons.toggle(dock, service(it), services, 3, emptyList()) }
        assertEquals(listOf("Netflix", "Plex", "Kodi"), dock.map { it.label })
        dock = RemoteButtons.toggle(dock, service("Plex"), services, 3, emptyList())
        assertEquals(listOf("Netflix", "Kodi"), dock.map { it.label })
    }

    @Test fun aButtonOpensTheVariantTheTvHas() {
        val prime = service("Prime Video")
        val nvidia = AppInfo("com.amazon.amazonvideo.livingroom.nvidia", "Prime Video", null, false, true, null, "", launchable = true)
        assertEquals(nvidia.packageName, RemoteButtons.toggle(emptyList(), prime, services, 3, listOf(nvidia)).single().packageName)
        assertEquals(prime.packages.first(), prime.packageOn(emptyList()))
    }

    @Test fun thePickerOffersOnlyServicesOnTheTv() {
        assertFalse(RemoteButtons.ANY_SERVICE)
        val nvidia = AppInfo("com.amazon.amazonvideo.livingroom.nvidia", "Prime Video", null, false, true, null, "", launchable = true)
        assertEquals(listOf("Prime Video"), RemoteButtons.choices(services, listOf(nvidia)).map { it.name })
        assertEquals(emptyList<StreamingService>(), RemoteButtons.choices(services, emptyList()))
    }

    private val lightSheet = 0xFFEEF5F4
    private val darkSheet = 0xFF161D1D

    @Test fun aPaleColourGetsAnOutlineInLightMode() {
        assertTrue(RemoteButtons.look(service("YouTube"), lightSheet, dark = false).outline)
        assertTrue(RemoteButtons.look(service("Jellyfin"), lightSheet, dark = false).outline) // by its paler end
        assertFalse(RemoteButtons.look(service("Netflix"), lightSheet, dark = false).outline)
        assertNull(RemoteButtons.look(service("YouTube"), lightSheet, dark = false).ink)
    }

    @Test fun aDarkPillTakesItsAccentInDarkModeWithAReadableLogo() {
        val netflix = RemoteButtons.look(service("Netflix"), darkSheet, dark = true)
        assertEquals(0xFFE50914, netflix.fill)
        assertEquals(RemoteButtons.WHITE, netflix.ink)
        assertEquals(RemoteButtons.BLACK, RemoteButtons.look(service("Spotify"), darkSheet, dark = true).ink)
        // A white-only logo on black has no accent: it stays as it is, with no edge.
        val hbo = RemoteButtons.look(service("HBO Max"), darkSheet, dark = true)
        assertEquals(service("HBO Max").color, hbo.fill)
        assertFalse(hbo.outline)
        assertNull(hbo.ink)
        // Bright enough already: unchanged.
        assertEquals(service("Plex").color, RemoteButtons.look(service("Plex"), darkSheet, dark = true).fill)
    }

    @Test fun everyDarkFillReadsAgainstTheSheet() {
        services.mapNotNull { s -> s.dark?.let { s.name to it } }.forEach { (name, fill) ->
            assertTrue(name, RemoteButtons.contrast(fill, darkSheet) >= 3.0)
        }
    }
}
