// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.Changelog
import app.tellyscope.data.Changelog.Change
import app.tellyscope.data.Changelog.Kind
import app.tellyscope.data.RELEASES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogTest {

    private val sample = """
        # Changelog

        What changed in each Tellyscope release. Headings are `## <version> — <date>`.
        - not a change: before any heading

        ## Unreleased

        - **Fixed:** Not released yet. [#9](../../pull/9)

        ## 0.13.0 — 2026-10-29

        - **New:** Remote buttons open Netflix and more. [#224](../../pull/224)
          - Hold a button to pick up to three.
          - Screen off moved into ⋮.
        - **Fixed:** On Fire TV, Open opens the app. [#248](../../pull/248)

        ## 0.12.0 — 2026-10-28

        - **Improved:** The Remote opens as a sheet. [#198](../../pull/198)
    """.trimIndent()

    @Test fun readsReleasedSectionsInTheFilesOrder() {
        val releases = Changelog.parse(sample)
        assertEquals(listOf("0.13.0", "0.12.0"), releases.map { it.version })
        assertEquals(listOf("2026-10-29", "2026-10-28"), releases.map { it.date })
        assertEquals(
            listOf(
                Change(Kind.New, "Remote buttons open Netflix and more.", 224, listOf("Hold a button to pick up to three.", "Screen off moved into ⋮.")),
                Change(Kind.Fixed, "On Fire TV, Open opens the app.", 248),
            ),
            releases[0].changes,
        )
        assertEquals(listOf(Change(Kind.Improved, "The Remote opens as a sheet.", 198)), releases[1].changes)
    }

    @Test fun aPullRequestOpensOnTheMainRepo() {
        assertEquals(RELEASES.removeSuffix("/releases") + "/pull/249", Changelog.pullUrl(249))
        assertTrue(Changelog.pullUrl(249).endsWith("/tellyscope/pull/249"))
    }

    @Test fun aHeadingWithoutADateStillCounts() {
        val r = Changelog.parse("## 1.0.0\n\n- A line with no tag or link\n").single()
        assertEquals(null, r.date)
        assertEquals(Change(null, "A line with no tag or link", null), r.changes.single())
    }

    /** The bundled file itself: every released line has a tag and a PR, so the page never shows a bare line. */
    @Test fun theRepoChangelogParsesCleanly() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, Changelog.ASSET) }.first { it.exists() }
        val releases = Changelog.parse(file.readText())
        assertTrue(releases.isNotEmpty())
        assertTrue(releases.none { it.version.equals("Unreleased", ignoreCase = true) })
        val bare = releases.flatMap { r -> r.changes.filter { it.kind == null || it.pr == null }.map { "${r.version}: ${it.text}" } }
        assertEquals(emptyList<String>(), bare)
    }
}
