// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

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
    private val signer = Regex("[0-9a-f]{64}")
    // "." and ".." are not repo names; they would walk the URL out of the repo.
    private val releases = Regex("https://github\\.com/[A-Za-z0-9-]+/(?!\\.\\.?/)[A-Za-z0-9._-]+/releases")
    private val checked = Regex("[0-9][0-9A-Za-z.+-]{0,31}")

    /** The Remote's sheet in dark mode; a service's `dark` fill replaces a colour that sinks into it. */
    private val darkSheet = 0xFF161D1D

    private fun text(name: String) = File(data, name).readText()
    private fun schema1(name: String): JSONObject = JSONObject(text(name)).also { assertEquals("$name: schema", 1, it.optInt("schema")) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.nonEmpty(key: String) = (opt(key) as? String)?.isNotBlank() == true
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun assertNone(errors: List<String>) = assertEquals("", errors.joinToString("\n"))

    @Test fun launchers() {
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val fire = mutableListOf<String>()
        val entries = schema1("launchers.json").getJSONArray("launchers").objects()
        entries.forEachIndexed { i, l ->
            val where = "launchers.json: launcher ${i + 1}"
            val unknown = l.keys().asSequence().toSet() - setOf("package", "name", "about", "signers", "max_mb", "releases", "fire_os", "accessibility")
            if (unknown.isNotEmpty()) errors += "$where: unknown keys ${unknown.sorted()}"
            val pkg = l.optString("package")
            if (!package_.matches(pkg)) errors += "$where: not a package name: $pkg"
            if (!l.nonEmpty("name")) errors += "$where: 'name' must be a non-empty string"
            if (l.has("about") && !l.nonEmpty("about")) errors += "$where: 'about' must be a non-empty string"
            val signers = l.optJSONArray("signers")?.let { a -> (0 until a.length()).map { a.opt(it) } }
            if (l.has("signers") && (signers.isNullOrEmpty() || !signers.all { it is String && signer.matches(it) })) {
                errors += "$where: 'signers' must be a non-empty list of SHA-256s, 64 lowercase hex"
            }
            if (l.has("max_mb") && (l.opt("max_mb") as? Int)?.takeIf { it in 1..256 } == null) errors += "$where: 'max_mb' must be a whole number from 1 to 256"
            if (l.has("releases") && !releases.matches(l.optString("releases"))) errors += "$where: 'releases' must be https://github.com/<owner>/<repo>/releases"
            if (l.has("accessibility") && (l.opt("accessibility") as? String)?.takeIf(checked::matches) == null) {
                errors += "$where: 'accessibility' must be the minimum version that has the service, like \"4.72\""
            }
            if (l.has("fire_os") && l.opt("fire_os") != true) errors += "$where: 'fire_os' can only be true; leave it out otherwise"
            if (l.opt("fire_os") == true) {
                fire += pkg
                if (!l.has("signers")) errors += "$where: 'fire_os' needs 'signers', or Fire OS has nothing to check the APK against"
            }
            if (!seen.add(pkg)) errors += "$where: $pkg is listed twice"
        }
        if (fire.size > 1) errors += "launchers.json: 'fire_os' is on ${fire.size} launchers; Fire OS installs one"
        if (errors.isEmpty()) assertEquals("launchers.json: the app drops none", entries.size, parseLaunchers(text("launchers.json")).size)
        assertNone(errors)
    }

    /**
     * Named here on purpose, so a reviewer sees any launcher that gets to turn on an accessibility service,
     * which can read the screen. Adding one means adding it here too.
     */
    @Test fun theLaunchersWithAnAccessibilityServiceAreNamed() {
        val named = parseLaunchers(text("launchers.json")).filter { it.accessibility != null }.associate { it.pkg to it.accessibility }
        assertEquals(mapOf("com.spocky.projengmenu" to "4.72", "com.overdevs.at4k" to "1.2", "com.spauldhaliwal.dispatch" to "1.0.175"), named)
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

    /** The logos themselves live in tellyscope-devices, which checks them; here only their names. */
    @Test fun streaming() {
        val errors = mutableListOf<String>()
        val names = mutableSetOf<String>()
        val packages = mutableSetOf<String>()
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
                if (!names.add(s.optString("name"))) errors += "$where: listed twice"
            }
        }
        if (errors.isEmpty()) assertEquals("streaming.json: the app drops none", names.size, parseStreaming(text("streaming.json")).size)
        assertNone(errors)
    }

    /** `index.json` names every list with its sha256, as the app checks each download, and nothing else. */
    @Test fun theIndexMatchesTheFiles() {
        val errors = mutableListOf<String>()
        val index = parseIndex(text("index.json"))
        if (index == null) {
            assertNone(listOf("index.json: not a schema 1 index the app reads"))
            return
        }
        val expected = listOf("launchers", "labels", "streaming").associateWith { "$it.json" }
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
