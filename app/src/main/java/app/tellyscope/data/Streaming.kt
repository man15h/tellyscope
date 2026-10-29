// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import org.json.JSONObject

/**
 * A streaming service the Remote can have a button for, from `streaming.json`.
 * [packages] are its TV apps, the usual one first; a button opens the first one the TV has.
 * [color] is the pill's brand colour in both themes, a gradient towards [color2] when there is one.
 * [logo] names `logos/<logo>.png` ([SharedLists.logo]). [dark] fills the pill instead in dark mode, for a brand
 * colour that would sink into the sheet; the logo turns white or black on it.
 */
data class StreamingService(
    val name: String,
    val packages: List<String>,
    val color: Long,
    val color2: Long? = null,
    val logo: String,
    val dark: Long? = null,
    val group: String = "",
) {
    /** The app to open on a TV with [installed] packages: the first of ours it has, else the usual one. */
    fun packageOn(installed: Collection<String>): String = packages.firstOrNull { it in installed } ?: packages.first()
}

private val HEX = Regex("#[0-9A-Fa-f]{6}")
private val LOGO = Regex("[a-z0-9]+")

private fun JSONObject.colorOrNull(k: String): Long? =
    optStringOrNull(k)?.takeIf { HEX.matches(it) }?.let { 0xFF000000 or it.substring(1).toLong(16) }

/**
 * Empty unless it is schema 1. A service without a name, a package, a `#RRGGBB` colour or a logo
 * name is dropped, and so are packages that aren't package names.
 */
fun parseStreaming(text: String): List<StreamingService> = try {
    val o = jsonObject(text)
    if (o.optInt("schema") != 1) emptyList() else o.getJSONArray("groups").objectsOrSkip { it }.flatMap { g ->
        val group = plainText(g.getString("name"), NAME_MAX)
        g.getJSONArray("services").objectsOrSkip { s ->
            val pkgs = s.optJSONArray("packages")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { PACKAGE.matches(it) }
            StreamingService(
                name = plainText(s.getString("name"), NAME_MAX),
                packages = pkgs,
                color = s.colorOrNull("color") ?: return@objectsOrSkip null,
                color2 = s.colorOrNull("color2"),
                logo = s.getString("logo").takeIf { LOGO.matches(it) } ?: return@objectsOrSkip null,
                dark = s.colorOrNull("dark"),
                group = group,
            ).takeIf { it.name.isNotBlank() && it.packages.isNotEmpty() }
        }
    }
} catch (_: org.json.JSONException) {
    emptyList()
}

/** The Remote's row of streaming buttons, kept apart from the screen so it can be tested on its own. */
object RemoteButtons {
    /**
     * True: every service can be picked, installed or not, and a button stays when its app isn't on
     * the TV, so the whole list can be tried on one TV. False: the picker lists only what the TV has.
     */
    const val ANY_SERVICE = false

    /** Three buttons fit a phone; 600 dp and wider, a foldable or tablet, fits four. */
    fun cap(widthDp: Int): Int = if (widthDp >= 600) Dock.MAX else 3

    /** The service a dock entry stands for, by any of its packages. */
    fun serviceOf(entry: Docked, services: List<StreamingService>): StreamingService? =
        services.firstOrNull { entry.packageName in it.packages }

    /** The buttons to show, in order: only dock entries that are a known service, at most [cap]. */
    fun shown(dock: List<Docked>, services: List<StreamingService>, cap: Int): List<StreamingService> =
        dock.mapNotNull { serviceOf(it, services) }.distinct().take(cap)

    /** What the picker offers, in the file's order. */
    fun choices(services: List<StreamingService>, apps: List<AppInfo>): List<StreamingService> {
        if (ANY_SERVICE) return services
        val installed = apps.mapTo(HashSet()) { it.packageName }
        return services.filter { s -> s.packages.any { it in installed } }
    }

    /** Ticks or unticks [service]; a tick past [cap] does nothing. */
    fun toggle(dock: List<Docked>, service: StreamingService, services: List<StreamingService>, cap: Int, apps: List<AppInfo>): List<Docked> {
        val mine = dock.filter { serviceOf(it, services) != null }
        if (mine.any { it.packageName in service.packages }) return mine.filterNot { it.packageName in service.packages }
        if (mine.size >= cap) return mine
        return mine + Docked(service.packageOn(apps.map { it.packageName }), service.name)
    }

    /**
     * How a pill is drawn on a sheet of colour [sheet]: [fill] (to [fill2]), an [outline], and [ink]
     * for the logo, or null to draw it in its own colours.
     */
    data class Look(val fill: Long, val fill2: Long?, val outline: Boolean, val ink: Long?)

    /**
     * A brand colour under 3:1 against the sheet (the weaker end of a gradient) would sink into it.
     * In light mode it gets an outline; in dark mode it takes its [StreamingService.dark] fill,
     * with a white logo where white reads at 4.5:1 and black otherwise. A white-only logo on black
     * has no dark fill and stands on its own.
     */
    fun look(s: StreamingService, sheet: Long, dark: Boolean): Look {
        val plain = Look(s.color, s.color2, outline = false, ink = null)
        val weakest = listOfNotNull(s.color, s.color2).minOf { contrast(it, sheet) }
        if (weakest >= 3.0) return plain
        if (!dark) return plain.copy(outline = true)
        val fill = s.dark ?: return plain
        return Look(fill, null, outline = false, ink = if (contrast(WHITE, fill) >= 4.5) WHITE else BLACK)
    }

    const val WHITE = 0xFFFFFFFF
    const val BLACK = 0xFF000000

    /** WCAG contrast between two opaque colours. */
    fun contrast(a: Long, b: Long): Double {
        val (x, y) = luminance(a) to luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFFL) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }
}
