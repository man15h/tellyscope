// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

/**
 * CHANGELOG.md, bundled as an asset at build time, read for What's new. Only the released sections
 * count: the header paragraph and `## Unreleased` are left out. Everything stays in the file's order.
 */
object Changelog {
    const val ASSET = "CHANGELOG.md"

    /**
     * The pull request on the main repo. The file's own links are relative, so GitHub resolves them
     * against whichever fork shows it: only the number is kept.
     */
    fun pullUrl(pr: Int) = RELEASES.removeSuffix("/releases") + "/pull/$pr"

    enum class Kind { New, Improved, Fixed }

    /** One change. [pr] is the pull request it links to, if any; [details] are its indented points. */
    data class Change(val kind: Kind?, val text: String, val pr: Int?, val details: List<String> = emptyList())

    data class Release(val version: String, val date: String?, val changes: List<Change>)

    private val HEADING = Regex("""^## (.+?)(?: — (.+))?$""")
    private val ITEM = Regex("""^- (?:\*\*(\w+):\*\* )?(.*?)(?: \[#(\d+)]\([^)]*\))?$""")
    private val DETAIL = Regex("""^\s+- (.*)$""")

    fun parse(markdown: String): List<Release> {
        val releases = mutableListOf<Release>()
        var heading: Pair<String, String?>? = null
        val changes = mutableListOf<Change>()
        fun close() {
            heading?.let { (v, d) -> if (!v.equals("Unreleased", ignoreCase = true)) releases += Release(v, d, changes.toList()) }
            changes.clear()
        }
        for (line in markdown.lineSequence().map(String::trimEnd)) {
            val h = HEADING.matchEntire(line)
            val d = DETAIL.matchEntire(line)
            val i = ITEM.matchEntire(line)
            when {
                h != null -> { close(); heading = h.groupValues[1].trim() to h.groupValues[2].ifBlank { null } }
                heading == null -> {}
                d != null -> changes.removeLastOrNull()?.let { changes += it.copy(details = it.details + d.groupValues[1]) }
                i != null -> changes += Change(
                    Kind.entries.firstOrNull { it.name == i.groupValues[1] }, i.groupValues[2], i.groupValues[3].toIntOrNull(),
                )
            }
        }
        close()
        return releases
    }
}
