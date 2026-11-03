// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.Png
import app.tellyscope.data.RemoteButtons
import app.tellyscope.data.parseIndex
import app.tellyscope.data.parseLabels
import app.tellyscope.data.parseLaunchers
import app.tellyscope.data.parseStreaming
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * The real lists in `data/`, which the app downloads from `main`: the app drops what it can't read,
 * so a bad entry would vanish quietly. Each test collects every problem and fails on all of them.
 * org.json is Android's, so this runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataListsTest {
    private val data = File("../data")
    private val package_ = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val logoName = Regex("[a-z0-9]+")
    private val hex = Regex("#[0-9A-Fa-f]{6}")

    /** The Remote's sheet in dark mode; a service's `dark` fill replaces a colour that sinks into it. */
    private val darkSheet = 0xFF161D1D

    /** Logos are drawn at four times their size in dp and have to fit the smallest pill, 72 × 30 dp. */
    private val logoMax = 72 * 4 to 30 * 4

    private fun text(name: String) = File(data, name).readText()
    private fun schema1(name: String): JSONObject = JSONObject(text(name)).also { assertEquals("$name: schema", 1, it.optInt("schema")) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.nonEmpty(key: String) = (opt(key) as? String)?.isNotBlank() == true
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun assertNone(errors: List<String>) = assertEquals("", errors.joinToString("\n"))

    @Test fun launchers() {
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val entries = schema1("launchers.json").getJSONArray("launchers").objects()
        entries.forEachIndexed { i, l ->
            val where = "launchers.json: launcher ${i + 1}"
            val unknown = l.keys().asSequence().toSet() - setOf("package", "name", "about")
            if (unknown.isNotEmpty()) errors += "$where: unknown keys ${unknown.sorted()}"
            val pkg = l.optString("package")
            if (!package_.matches(pkg)) errors += "$where: not a package name: $pkg"
            if (!l.nonEmpty("name")) errors += "$where: 'name' must be a non-empty string"
            if (l.has("about") && !l.nonEmpty("about")) errors += "$where: 'about' must be a non-empty string"
            if (!seen.add(pkg)) errors += "$where: $pkg is listed twice"
        }
        if (errors.isEmpty()) assertEquals("launchers.json: the app drops none", entries.size, parseLaunchers(text("launchers.json")).size)
        assertNone(errors)
    }

    @Test fun labels() {
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (g in schema1("labels.json").getJSONArray("groups").objects()) {
            val labels = g.getJSONObject("labels")
            for (pkg in labels.keys()) {
                val where = "labels.json: '${g.optString("name")}'"
                if (pkg != "android" && !package_.matches(pkg)) errors += "$where: not a package name: $pkg"
                if (!labels.nonEmpty(pkg)) errors += "$where: $pkg needs a name"
                if (!seen.add(pkg)) errors += "$where: $pkg is listed twice"
            }
        }
        if (errors.isEmpty()) assertEquals("labels.json: the app drops none", seen.size, parseLabels(text("labels.json")).size)
        assertNone(errors)
    }

    @Test fun streamingAndLogos() {
        val errors = mutableListOf<String>()
        val names = mutableSetOf<String>()
        val packages = mutableSetOf<String>()
        val used = mutableSetOf<String>()
        for (g in schema1("streaming.json").getJSONArray("groups").objects()) {
            for (s in g.getJSONArray("services").objects()) {
                val where = "streaming.json: '${s.optString("name")}'"
                if (!s.nonEmpty("name")) errors += "$where: 'name' must be a non-empty string"
                val pkgs = s.optJSONArray("packages")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                if (pkgs.isEmpty()) errors += "$where: 'packages' must list at least one package"
                for (p in pkgs) {
                    if (!package_.matches(p)) errors += "$where: not a package name: $p"
                    if (!packages.add(p)) errors += "$where: $p belongs to another service too"
                }
                for (key in listOf("color", "color2", "dark")) {
                    if ((key == "color" || s.has(key)) && !hex.matches(s.optString(key))) errors += "$where: '$key' must be #RRGGBB"
                }
                s.optString("dark").takeIf { hex.matches(it) }?.let { dark ->
                    val c = RemoteButtons.contrast(0xFF000000 or dark.substring(1).toLong(16), darkSheet)
                    if (c < 3) errors += "$where: 'dark' is ${"%.2f".format(c)}:1 against the dark sheet, under 3:1"
                }
                val logo = s.optString("logo")
                if (!logoName.matches(logo)) errors += "$where: 'logo' must be lowercase letters and digits"
                else if (!File(data, "logos/$logo.png").isFile) errors += "$where: logos/$logo.png is missing"
                used += logo
                if (!names.add(s.optString("name"))) errors += "$where: listed twice"
            }
        }
        if (errors.isEmpty()) assertEquals("streaming.json: the app drops none", names.size, parseStreaming(text("streaming.json")).size)

        val rows = Regex("^\\| *([a-z0-9]+) *\\|", RegexOption.MULTILINE).findAll(text("logos/SOURCES.md")).map { it.groupValues[1] }.toSet()
        for (f in File(data, "logos").listFiles().orEmpty().sortedBy { it.name }) {
            if (f.name == "SOURCES.md") continue
            val where = "logos/${f.name}"
            if (f.nameWithoutExtension !in rows) errors += "$where: needs a row in logos/SOURCES.md with its source and licence"
            if (f.extension != "png" || !logoName.matches(f.nameWithoutExtension)) {
                errors += "$where: logos are <lowercase letters and digits>.png"
                continue
            }
            val b = f.readBytes()
            val size = Png.size(b)
            if (size == null) errors += "$where: not a PNG"
            else if (size.first > logoMax.first || size.second > logoMax.second) errors += "$where: ${size.first}x${size.second} is over ${logoMax.first}x${logoMax.second}"
            if (!Png.ok(b)) errors += "$where: the app won't show it (Png.ok)"
            if (f.nameWithoutExtension !in used) errors += "$where: no service in streaming.json uses it"
        }
        assertNone(errors)
    }

    /** `index.json` names every list and logo with its sha256, as the app checks each download. */
    @Test fun theIndexMatchesTheFiles() {
        val errors = mutableListOf<String>()
        val index = parseIndex(text("index.json"))
        if (index == null) {
            assertNone(listOf("index.json: not a schema 1 index the app reads"))
            return
        }
        val expected = listOf("launchers", "labels", "streaming").associateWith { "$it.json" } +
            File(data, "logos").listFiles().orEmpty().filter { it.extension == "png" }.associate { "logo ${it.nameWithoutExtension}" to "logos/${it.name}" }
        val listed = index.lists + index.logos.mapKeys { "logo ${it.key}" }
        for ((name, path) in expected) {
            val entry = listed[name]
            val sha = sha256(File(data, path).readBytes())
            when {
                entry == null -> errors += "index.json: $name ($path) is missing"
                entry.file != path -> errors += "index.json: $name names ${entry.file}, not $path"
                entry.sha256 != sha -> errors += "index.json: $path's sha256 is $sha"
            }
        }
        (listed.keys - expected.keys).forEach { errors += "index.json: $it isn't in data/" }
        assertNone(errors)
    }
}
