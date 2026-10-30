// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import androidx.compose.ui.text.font.FontWeight
import app.tellyscope.data.RELEASES
import app.tellyscope.data.UpdateCheck
import app.tellyscope.data.compareVersions
import app.tellyscope.data.newest
import app.tellyscope.ui.boldSpans
import app.tellyscope.ui.dialogNotes
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
import java.io.IOException

/** org.json is Android's, so this runs under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    private val MORE = Strings.string(R.string.update_more_notes)

    private val minute = 60 * 1000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun release(tag: String, pre: Boolean = tag.contains("-alpha"), notes: String = "Notes for $tag") =
        """{"tag_name":"$tag","prerelease":$pre,"draft":false,"body":"$notes"}"""

    private fun releases(vararg r: String) = "[" + r.joinToString(",") + "]"

    @Test fun `alpha sorts before its release, which sorts before the next alpha`() {
        assertTrue(compareVersions("0.2.0-alpha.3", "0.2.0") < 0)
        assertTrue(compareVersions("0.2.0", "0.2.1-alpha.1") < 0)
        assertTrue(compareVersions("0.2.0-alpha.3", "0.2.1-alpha.1") < 0)
        assertTrue(compareVersions("0.2.0-alpha.2", "0.2.0-alpha.10") < 0)
        assertTrue(compareVersions("0.10.0", "0.9.9") > 0)
        assertEquals(0, compareVersions("0.2.0", "0.2.0"))
    }

    @Test fun `release builds see full releases only, alpha builds see alphas too`() {
        val list = releases(release("v0.2.1-alpha.1"), release("v0.2.0"), release("v0.2.0-alpha.3"), release("v0.1.0"))
        assertEquals("0.2.0", newest(list, alpha = false)?.version)
        assertEquals("0.2.1-alpha.1", newest(list, alpha = true)?.version)
    }

    @Test fun `odd tags, drafts and pre-releases without an alpha tag are ignored`() {
        val list = releases(
            release("v9.0.0-beta"),
            release("nightly"),
            release("v3.0.0", pre = true),
            """{"tag_name":"v4.0.0","prerelease":false,"draft":true,"body":""}""",
            release("v0.2.0"),
        )
        assertEquals("0.2.0", newest(list, alpha = true)?.version)
        assertNull(newest("not json", alpha = false))
    }

    @Test fun `offers a newer release with its notes and page`() = runTest {
        val u = UpdateCheck(tmp.newFile(), "0.2.0-alpha.1", alpha = true, fetch = { releases(release("v0.2.0")).toByteArray() }).check()
        assertEquals("0.2.0", u?.version)
        assertEquals("Notes for v0.2.0", u?.notes)
        assertTrue(u!!.url.endsWith("/tellyscope/releases/tag/v0.2.0"))
    }

    @Test fun `the release's one APK comes along, only from this repo's releases`() {
        val base = RELEASES + "/download/v0.2.0/"
        fun withAsset(url: String, size: Long) =
            releases("""{"tag_name":"v0.2.0","prerelease":false,"draft":false,"body":"","assets":[{"name":"Tellyscope-0.2.0.apk","browser_download_url":"$url","size":$size}]}""")
        val u = newest(withAsset(base + "Tellyscope-0.2.0.apk", 3_000_000), alpha = false)
        assertEquals(base + "Tellyscope-0.2.0.apk", u?.apk)
        assertNull(newest(withAsset("https://example.com/Tellyscope-0.2.0.apk", 3_000_000), alpha = false)?.apk)
        assertNull(newest(withAsset(base + "Tellyscope-0.2.0.apk", 0), alpha = false)?.apk)
        assertNull(newest(releases(release("v0.2.0")), alpha = false)?.apk)
        // An alpha build is a different package: it's offered the release, but only its page.
        val offered = newest(withAsset(base + "Tellyscope-0.2.0.apk", 3_000_000), alpha = true)
        assertEquals("0.2.0", offered?.version)
        assertNull(offered?.apk)
    }

    @Test fun `nothing when the running version is the newest`() = runTest {
        val check = UpdateCheck(tmp.newFile(), "0.2.0", alpha = false, fetch = { releases(release("v0.2.0"), release("v0.1.0")).toByteArray() })
        assertNull(check.check())
    }

    @Test fun `a release build asks again after 30 minutes, not before`() = runTest {
        var now = 1_000_000L
        var calls = 0
        val check = UpdateCheck(tmp.newFile(), "0.1.0", alpha = false, clock = { now }) { calls++; releases(release("v0.2.0")).toByteArray() }
        assertEquals("0.2.0", check.check()?.version)
        now += 30 * minute - 1
        assertNull(check.check())
        assertEquals(1, calls)
        now += 1
        assertEquals("0.2.0", check.check()?.version)
        assertEquals(2, calls)
    }

    @Test fun `an alpha build asks again after 5 minutes`() = runTest {
        var now = 1_000_000L
        var calls = 0
        val check = UpdateCheck(tmp.newFile(), "0.2.0-alpha.2", alpha = true, clock = { now }) { calls++; releases(release("v0.2.0-alpha.3")).toByteArray() }
        assertEquals("0.2.0-alpha.3", check.check()?.version)
        now += 5 * minute - 1
        assertNull(check.check())
        now += 1
        assertEquals("0.2.0-alpha.3", check.check()?.version)
        assertEquals(2, calls)
    }

    @Test fun `a failed request is silent and tried again next time`() = runTest {
        var fail = true
        val check = UpdateCheck(tmp.newFile(), "0.1.0", alpha = false) {
            if (fail) throw IOException("offline")
            releases(release("v0.2.0")).toByteArray()
        }
        assertNull(check.check())
        fail = false
        assertEquals("0.2.0", check.check()?.version)
    }

    @Test fun `on by default, and off means no request`() = runTest {
        val file = tmp.newFile()
        var calls = 0
        val check = UpdateCheck(file, "0.1.0", alpha = false) { calls++; releases(release("v0.2.0")).toByteArray() }
        assertTrue(check.enabled)
        check.enabled = false
        assertFalse(UpdateCheck(file, "0.1.0", alpha = false).enabled)
        assertNull(check.check())
        assertEquals(0, calls)
    }

    @Test fun `newer reads main's CHANGELOG and keeps only releases past the installed one`() = runTest {
        val urls = mutableListOf<String>()
        val md = "# Changelog\n\n## Unreleased\n\n- **New:** Later.\n\n## 0.3.0 — 2026-10-30\n\n- **New:** Three.\n\n## 0.2.0 — 2026-10-29\n\n- **New:** Two.\n"
        val newer = UpdateCheck(tmp.newFile(), "0.2.0", alpha = false) { urls += it; md.toByteArray() }.newer()
        assertEquals(listOf("0.3.0"), newer?.map { it.version })
        assertTrue(urls.single().endsWith("/main/CHANGELOG.md"))
        // An alpha reads main too, and its own section is its base version's.
        val alpha = UpdateCheck(tmp.newFile(), "0.2.0-alpha.4", alpha = true) { urls += it; md.toByteArray() }.newer()
        assertEquals(listOf("0.3.0"), alpha?.map { it.version })
        assertTrue(urls.last().endsWith("/main/CHANGELOG.md"))
        assertNull(UpdateCheck(tmp.newFile(), "0.2.0", alpha = false) { throw IOException("offline") }.newer())
    }

    /** v0.1.0's body as release.yml wrote it, commit list cut short. */
    private val prodBody = """

        - **New:** A new app icon: a telescope with a TV screen for a lens. [#28](../../pull/28)
        - **New:** Install apps on the TV from an APK, split APKs or a bundle. [#9](../../pull/9)
        - **New:** Send files to the TV's Download or Movies folder, and save them back. [#9](../../pull/9)
        - **New:** Suggested apps to disable, from a tested list for your TV. [#11](../../pull/11)
        - **Fixed:** Processes, screen state, connections and data used now show on real TVs. [#17](../../pull/17)
        - **Fixed:** A TV on Wi-Fi no longer shows its connection as Ethernet. [#17](../../pull/17)
        - **New:** An icon shows whether each device is a TV, projector, Fire TV or stick. [#18](../../pull/18)
        - **Improved:** The confirm before disabling suggested apps names every app. [#19](../../pull/19)
        - **Improved:** System updaters, Wi-Fi resources and Chrome's library are now protected. [#19](../../pull/19)
        - **Fixed:** Done and error messages stay on screen long enough to read. [#20](../../pull/20)
        - **Fixed:** Undo and results always go to the TV the action ran on. [#20](../../pull/20)
        - **New:** Debloat in two steps: pick your TV, then review every app it turns off. [#23](../../pull/23)
        - **Improved:** The TV page leads with the TV, its vitals and big action buttons. [#23](../../pull/23)
        - **Fixed:** Adding a TV with a port above 65535 shows a message, not a crash. [#25](../../pull/25)
        - **Fixed:** Losing Wi-Fi while pairing shows a message, not a crash. [#25](../../pull/25)
        - **Fixed:** A broken suggestions list can no longer crash the app. [#25](../../pull/25)
        - **Improved:** Undoing a Turn on in History asks before disabling the app. [#25](../../pull/25)

        ### Commits since the first commit

        - Changelog: 0.1.0 — 2026-10-23 (c9a1fe7)
        - README: the icon and a real heading, in place of the logo header (ef66937)
    """.trimIndent()

    /** v0.2.0-alpha.1's body as release.yml wrote it, commit list cut short. */
    private val alphaBody = """
        Alpha build v0.2.0-alpha.1 from `abdb1a8` on dev. Untested and may break; it installs as a separate app, `app.tellyscope.alpha`.

        ### Commits since v0.1.0-alpha.3

        - CHANGELOG: home screen switch (abdb1a8)
        - Start 0.2.0 (4afecde)
    """.trimIndent()

    @Test fun `prod notes keep the changelog lines, plain, capped at twelve`() {
        val lines = dialogNotes(prodBody, MORE).lines()
        assertEquals(13, lines.size)
        assertEquals("- **New:** A new app icon: a telescope with a TV screen for a lens.", lines.first())
        assertEquals("…and more on GitHub", lines.last())
        assertTrue(lines.none { "](" in it || "Commits" in it })
    }

    @Test fun `alpha notes are just the alpha line`() {
        assertEquals(
            "Alpha build v0.2.0-alpha.1 from abdb1a8 on dev. Untested and may break; it installs as a separate app, app.tellyscope.alpha.",
            dialogNotes(alphaBody, MORE),
        )
    }

    @Test fun `short notes are not cut`() {
        assertEquals("- **Fixed:** one thing.", dialogNotes("- **Fixed:** one thing. [#9](../../pull/9)\n\n### Commits since v1\n\n- x (abc)", MORE))
    }

    @Test fun `New is bold with the stars gone`() {
        val first = boldSpans(dialogNotes(prodBody, MORE)).let { it.subSequence(0, it.text.indexOf('\n')) }
        assertEquals("- New: A new app icon: a telescope with a TV screen for a lens.", first.text)
        assertFalse('*' in boldSpans(dialogNotes(prodBody, MORE)).text)
        val bold = first.spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals("New:", first.text.substring(bold.start, bold.end))
    }

    @Test fun `an unmatched pair of stars is dropped`() {
        val s = boldSpans("a **b** c ** d")
        assertEquals("a b c  d", s.text)
        assertEquals("b", s.spanStyles.single().let { s.text.substring(it.start, it.end) })
    }
}
