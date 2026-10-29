// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Where the per-TV debloat lists live, with the launcher, app name and streaming lists and the
 * Remote's logos: the tellyscope-devices repo.
 */
const val LISTS_BASE = "https://raw.githubusercontent.com/man15h/tellyscope-devices/main/"

/** One row of `index.json`. [match] is null for lists nobody has matched to a TV yet. */
data class ListEntry(
    val id: String,
    val name: String,
    val match: Map<String, List<String>>?,
    val sdk: Int?,
    val file: String,
    val sha256: String,
)

/**
 * Who makes each list's TV, by id. Lists carry no maker, so it is the name's first word, unless
 * no list's id starts with that word and the list's own id prefix is a later word in the name:
 * "Chromecast with Google TV" is Google's, while "Skyworth Coocaa K3" stays Skyworth's.
 */
fun listMakers(devices: List<ListEntry>): Map<String, String> {
    fun key(w: String) = squash(w)
    val prefixes = devices.map { it.id.substringBefore('-') }.toSet()
    return devices.associate { e ->
        val words = e.name.split(' ').filter { it.isNotBlank() }
        val prefix = e.id.substringBefore('-')
        val first = words.firstOrNull() ?: prefix
        e.id to if (key(first) in prefixes) first else words.firstOrNull { key(it) == prefix } ?: first
    }
}

/** Lowercase letters and digits only, so "MiTV-AXFR0" and "mitv axfr0" compare equal. */
fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/** How a TV's list was found: one list for its model, every list from its maker, or none. */
sealed interface ListMatch {
    data class Model(val entry: ListEntry) : ListMatch
    data class Maker(val maker: String, val entries: List<ListEntry>) : ListMatch
    data object None : ListMatch
}

/** True when [model], squashed, is a run of whole consecutive [words]: "MoGo 2" fits "XGIMI MoGo 2", "X10" doesn't fit "TCL X100". */
private fun wordsFit(model: String, words: List<String>): Boolean = words.indices.any { i ->
    var run = ""
    words.drop(i).any { w -> run += squash(w); run == model }
}

/** The TV's maker names: `ro.product.manufacturer`, then `ro.product.brand`, which can differ. */
private fun tvMakers(props: Map<String, String?>) = listOf("manufacturer", "brand").mapNotNull { squash(props[it].orEmpty()).ifEmpty { null } }.distinct()

/**
 * The one list whose name (by words) or id (by `-` parts) holds the TV's model as whole words.
 * Null when none or several do, or when that list's maker isn't the TV's: a wrong list is worse
 * than the maker's lists, which start unticked.
 */
fun byModel(devices: List<ListEntry>, props: Map<String, String?>): ListEntry? {
    val model = squash(props["model"].orEmpty()).takeIf { it.length >= 3 } ?: return null
    val fit = devices.filter { wordsFit(model, it.name.split(' ')) || wordsFit(model, it.id.split('-')) }.singleOrNull() ?: return null
    return fit.takeIf { squash(listMakers(devices).getValue(it.id)) in tvMakers(props) }
}

/** Every list from the TV's maker, by [listMakers]: its manufacturer's, else its brand's. */
fun byMaker(devices: List<ListEntry>, props: Map<String, String?>): List<ListEntry> {
    val makers = listMakers(devices)
    return tvMakers(props).firstNotNullOfOrNull { m -> devices.filter { squash(makers.getValue(it.id)) == m }.ifEmpty { null } }.orEmpty()
}

/**
 * A maker's lists as one: groups of the same title merged, each app once, and every list's keep
 * entries together, so a keep in any of them protects the app. No tested Android, so nothing
 * gets ticked.
 */
fun merge(maker: String, lists: List<DeviceList>): DeviceList = DeviceList(
    id = "maker:" + squash(maker),
    name = maker,
    testedOs = null,
    sdk = null,
    keep = lists.flatMap { it.keep }.toSet(),
    groups = lists.flatMap { it.groups }.groupBy { it.title }.map { (title, gs) ->
        ListGroup(title, gs.flatMap { it.packages }.distinctBy { it.pkg })
    },
)

/** A file the index names outside `devices/`, checked against [sha256] like a device file. */
data class ListFile(val file: String, val sha256: String)

/** [lists] by name (`launchers`, `labels`, `streaming`) and [logos] by logo name; empty on an index from before they moved here. */
data class ListIndex(
    val commit: String?,
    val devices: List<ListEntry>,
    val lists: Map<String, ListFile> = emptyMap(),
    val logos: Map<String, ListFile> = emptyMap(),
)

data class ListPackage(val pkg: String, val note: String?, val optional: Boolean)

data class ListGroup(val title: String, val packages: List<ListPackage>)

data class DeviceList(
    val id: String,
    val name: String,
    val testedOs: String?,
    val sdk: Int?,
    /** Packages the list says must stay on. They can only add protection. */
    val keep: Set<String>,
    val groups: List<ListGroup>,
)

data class Suggested(val pkg: String, val label: String, val note: String?, val optional: Boolean, val ticked: Boolean)

data class SuggestedGroup(val title: String, val packages: List<Suggested>)

/** What the Overview's list card shows. */
sealed interface Suggestions {
    /** No index (offline and nothing cached), or the chosen list failed its checksum. */
    data object Unavailable : Suggestions

    /** No list for this TV's model or maker, and none was picked: clean up by hand in Apps. */
    data class NoList(val commit: String?, val devices: List<ListEntry>) : Suggestions

    data class Ready(
        val commit: String?,
        val list: DeviceList,
        /** The TV's SDK; ticks only when it equals [DeviceList.sdk]. */
        val sdk: Int?,
        val groups: List<SuggestedGroup>,
        /** Every list, for "Not this TV?". */
        val devices: List<ListEntry>,
        /** [list] is [merge] of the maker's lists, as no list fits this model. */
        val byMaker: Boolean = false,
    ) : Suggestions {
        val sdkMatches get() = sdk != null && sdk == list.sdk
    }
}

/** Same rule tellyscope-devices checks; a name goes into a `pm` shell line. */
internal val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
private val FILE = Regex("devices/[A-Za-z0-9][A-Za-z0-9._-]*\\.json")
private val LIST_FILE = Regex("lists/[a-z]+\\.json")
private val LOGO_FILE = Regex("logos/[a-z0-9]+\\.png")
private val SHA256 = Regex("[0-9a-f]{64}")
/** A list id goes into `picks.tsv`, one tab-separated line per TV, so no tab or newline. */
private val LIST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val COMMIT = Regex("[0-9a-f]{7,40}")
private const val SCHEMA = 1

/** The props a match rule may name, read from `ro.product.*`; brand also finds the maker's lists. */
val MATCH_KEYS = listOf("manufacturer", "model", "device", "brand")

/** A rule matches when every key in it equals one of its values, ignoring case. An empty rule never does. */
fun matches(rule: Map<String, List<String>>?, props: Map<String, String?>): Boolean =
    !rule.isNullOrEmpty() && rule.all { (key, values) ->
        val actual = props[key] ?: return@all false
        values.any { it.equals(actual, ignoreCase = true) }
    }

/**
 * Null unless it is a schema 1 index. Entries with a missing field, bad file name or checksum are
 * dropped, in `devices`, `lists` and `logos` alike.
 */
fun parseIndex(text: String): ListIndex? = try {
    val o = jsonObject(text)
    if (o.optInt("schema") != SCHEMA) null else ListIndex(
        commit = o.optJSONObject("source")?.optStringOrNull("commit")?.takeIf { COMMIT.matches(it) },
        devices = o.getJSONArray("devices").objectsOrSkip { d ->
            ListEntry(
                id = d.getString("id"),
                name = plainText(d.getString("name"), NAME_MAX),
                match = d.optJSONObject("match")?.let { m ->
                    m.keys().asSequence().associateWith { k -> m.getJSONArray(k).strings() }
                },
                sdk = d.optIntOrNull("sdk"),
                file = d.getString("file"),
                sha256 = d.getString("sha256"),
            ).takeIf { LIST_ID.matches(it.id) && it.name.isNotEmpty() && FILE.matches(it.file) && SHA256.matches(it.sha256) }
        },
        lists = o.files("lists", LIST_FILE),
        logos = o.files("logos", LOGO_FILE),
    )
} catch (_: org.json.JSONException) {
    null
}

/** The `{name: {file, sha256}}` map under [key], without entries whose file isn't [pattern] or whose checksum can't be one. */
private fun JSONObject.files(key: String, pattern: Regex): Map<String, ListFile> {
    val o = optJSONObject(key) ?: return emptyMap()
    return o.keys().asSequence().mapNotNull { k ->
        val f = o.optJSONObject(k) ?: return@mapNotNull null
        val file = ListFile(f.optString("file"), f.optString("sha256"))
        (k to file).takeIf { pattern.matches(file.file) && SHA256.matches(file.sha256) }
    }.toMap()
}

/**
 * Null unless it is a schema 1 device file for [id]. Entries that can't be read and names that
 * aren't package names are dropped.
 */
fun parseList(text: String, id: String): DeviceList? = try {
    val o = jsonObject(text)
    val tested = o.optJSONObject("tested")
    fun packages(a: JSONArray?) = a?.objectsOrSkip { ListPackage(it.getString("pkg"), it.optStringOrNull("note")?.let { n -> plainText(n, NOTE_MAX) }, it.optBoolean("optional")) }
        .orEmpty().filter { PACKAGE.matches(it.pkg) }
    if (o.optInt("schema") != SCHEMA || o.optString("id") != id) null else DeviceList(
        id = id,
        name = plainText(o.getString("name"), NAME_MAX),
        testedOs = tested?.optStringOrNull("os")?.let { plainText(it, NAME_MAX) },
        sdk = tested?.optIntOrNull("sdk"),
        keep = packages(o.optJSONArray("keep")).map { it.pkg }.toSet(),
        groups = o.getJSONArray("groups").objectsOrSkip { g ->
            ListGroup(plainText(g.optString("title"), NAME_MAX), packages(g.optJSONArray("packages")))
        }.filter { it.packages.isNotEmpty() },
    )
} catch (_: org.json.JSONException) {
    null
}

/**
 * The list cut down to this TV: only apps it actually has, still on, and not protected (built in
 * or by the list's keep), and never one of the TV's own launchers. Pre-ticked only for
 * non-optional entries on a TV with the SDK the list was tested on. Groups with the same title become one, and an app shows once, in its first group,
 * since the screen keys rows by title and package.
 */
fun suggest(list: DeviceList, apps: List<AppInfo>, sdk: Int?): List<SuggestedGroup> {
    val installed = apps.associateBy { it.packageName }
    val tick = sdk != null && sdk == list.sdk
    val seen = mutableSetOf<String>()
    return list.groups.groupBy { it.title }.map { (title, gs) ->
        SuggestedGroup(title, gs.flatMap { it.packages }.mapNotNull { p ->
            val app = installed[p.pkg]?.takeIf { it.enabled && it.protectedReason == null && !it.builtInLauncher && p.pkg !in list.keep }
            app?.takeIf { seen.add(p.pkg) }?.let { Suggested(p.pkg, it.label, p.note, p.optional, ticked = tick && !p.optional) }
        })
    }.filter { it.packages.isNotEmpty() }
}

/**
 * Fetches and caches the index, device files, lists and logos under [dir]. A failed fetch falls
 * back to the cache; a file is only used (and cached) when its sha256 matches the index. Hand picks
 * are remembered per [historyKey].
 */
class DeviceLists(
    private val dir: File,
    private val fetch: suspend (String) -> ByteArray = ::download,
) {
    private val lock = Mutex()
    private var index: ListIndex? = null
    private val picks = File(dir, "picks.tsv")
    private val sourceFile = File(dir, "source.txt")

    /** The folder holding `index.json`: one the user set, else [LISTS_BASE]. Checked again here, so a stored address that isn't `https` is never used. */
    val base: String get() = custom()?.let(::normalize) ?: LISTS_BASE

    /** The user's own source, or null for tellyscope-devices. */
    @Synchronized
    fun custom(): String? = sourceFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /**
     * Points the lists at [url], or back at [LISTS_BASE] with null. The new source must answer
     * with a schema 1 index first; otherwise nothing changes. The cached lists are dropped, so
     * nothing from the old source lingers; picks stay, and a pick the new source lacks falls
     * back to matching.
     */
    suspend fun setSource(url: String?): SourceResult = lock.withLock {
        val next = if (url == null) LISTS_BASE else normalize(url) ?: return@withLock SourceResult.NotHttps
        val text = attempt { fetch(next + "index.json") }?.decodeToString()
        // Going back to the default always works, offline too: its lists load once online.
        if (text == null && url != null) return@withLock SourceResult.Unreachable
        val parsed = text?.let(::parseIndex)
        if (text != null && parsed == null) return@withLock SourceResult.NotAList
        withContext(Dispatchers.IO) {
            File(dir, "index.json").delete()
            File(dir, "devices").deleteRecursively()
            File(dir, "lists").deleteRecursively()
            File(dir, "logos").deleteRecursively()
            dir.mkdirs()
            if (text != null) File(dir, "index.json").writeText(text)
            synchronized(this@DeviceLists) {
                if (next == LISTS_BASE) sourceFile.delete() else sourceFile.writeText(next)
            }
        }
        index = parsed
        SourceResult.Ok
    }

    /**
     * Fetched once per process; later calls reuse it. Offline, the cached copy is returned and the
     * next call tries the network again. Null when offline with no cache.
     */
    suspend fun index(): ListIndex? = lock.withLock {
        index ?: withContext(Dispatchers.IO) {
            dir.mkdirs()
            val cache = File(dir, "index.json")
            val text = attempt { fetch(base + "index.json") }?.decodeToString()
            text?.let(::parseIndex)?.also { cache.writeText(text); index = it }
                ?: cache.takeIf { it.exists() }?.readText()?.let(::parseIndex)
        }
    }

    /** The device file for [entry], from cache or the network. Null when the bytes don't match its sha256. */
    suspend fun list(entry: ListEntry): DeviceList? =
        file(entry.file, entry.sha256)?.let { parseList(it.decodeToString(), entry.id) }

    /**
     * The launcher, app name and streaming lists the index names, each from cache or the network.
     * One that can't be had is null in [SharedFiles], so the caller keeps what it has.
     */
    suspend fun shared(): SharedFiles {
        val lists = index()?.lists.orEmpty()
        suspend fun get(name: String) = lists[name]?.let { file(it.file, it.sha256) }?.decodeToString()
        return SharedFiles(get("launchers"), get("labels"), get("streaming"))
    }

    /** The lists as last cached, without the network, for the moment before [shared] answers; each only while it matches the cached index. */
    suspend fun cachedShared(): SharedFiles = withContext(Dispatchers.IO) {
        val lists = File(dir, "index.json").takeIf { it.exists() }?.readText()?.let(::parseIndex)?.lists.orEmpty()
        fun read(name: String) = lists[name]?.let { f -> cached(f.file)?.takeIf { sha256(it) == f.sha256 } }?.decodeToString()
        SharedFiles(read("launchers"), read("labels"), read("streaming"))
    }

    /**
     * A logo's PNG by the name `streaming.json` gives it; null when the index has none or it can't be
     * had. Uses the index already read, never fetching it: offline, every button on screen asking at
     * once would queue one network timeout each behind [lock].
     */
    suspend fun logo(name: String): ByteArray? {
        val known = index ?: withContext(Dispatchers.IO) { File(dir, "index.json").takeIf { it.exists() }?.readText()?.let(::parseIndex) }
        return known?.logos?.get(name)?.let { file(it.file, it.sha256, Png::ok) }
    }

    /**
     * [path] under [dir] when its bytes match [sha] and pass [valid], else fetched from [base] and
     * cached if they do. Null for a path that isn't one the index may name or that leaves [dir].
     */
    private suspend fun file(path: String, sha: String, valid: (ByteArray) -> Boolean = { true }): ByteArray? = withContext(Dispatchers.IO) {
        val cache = cacheFile(path) ?: return@withContext null
        fun ok(b: ByteArray) = sha256(b) == sha && valid(b)
        cache.takeIf { it.exists() }?.readBytes()?.takeIf(::ok)
            ?: attempt { fetch(base + path) }?.takeIf(::ok)
                ?.also { cache.parentFile?.mkdirs(); cache.writeBytes(it) }
    }

    private fun cached(path: String): ByteArray? = cacheFile(path)?.takeIf { it.exists() }?.readBytes()

    /** Where [path] is cached: only a name [parseIndex] keeps, and only inside [dir] once resolved. */
    private fun cacheFile(path: String): File? {
        if (!FILE.matches(path) && !LIST_FILE.matches(path) && !LOGO_FILE.matches(path)) return null
        val root = dir.canonicalFile
        return File(root, path).canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    /**
     * The list for a TV: the one picked by hand for [key], else the first whose rule matches
     * [props], else the one named after its model. Without one, every list from its maker.
     */
    fun find(index: ListIndex, key: String, props: Map<String, String?>): ListMatch {
        val one = picked(key)?.let { id -> index.devices.firstOrNull { it.id == id } }
            ?: index.devices.firstOrNull { matches(it.match, props) }
            ?: byModel(index.devices, props)
        if (one != null) return ListMatch.Model(one)
        val maker = byMaker(index.devices, props)
        return if (maker.isEmpty()) ListMatch.None else ListMatch.Maker(listMakers(index.devices).getValue(maker.first().id), maker)
    }

    @Synchronized
    fun picked(key: String): String? = readPicks()[key]

    @Synchronized
    fun pick(key: String, id: String) {
        dir.mkdirs()
        val all = readPicks() + (key to id)
        val tmp = File(dir, "picks.tsv.tmp")
        tmp.writeText(all.entries.joinToString("") { (k, v) -> "$k\t$v\n" })
        tmp.renameTo(picks)
    }

    private fun readPicks(): Map<String, String> = if (!picks.exists()) emptyMap() else
        picks.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** The raw text of each shared list; null for one not available. */
data class SharedFiles(val launchers: String?, val labels: String?, val streaming: String?)

enum class SourceResult { Ok, NotHttps, Unreachable, NotAList }

const val NOT_A_LIST = "this source isn't a Tellyscope list"

/**
 * An `https://` folder address ending in `/`, or null for anything else. A pasted `index.json`
 * address is cut back to its folder.
 */
fun normalize(url: String): String? {
    val u = url.trim().removeSuffix("index.json")
    val parsed = try {
        java.net.URI(u)
    } catch (_: java.net.URISyntaxException) {
        return null
    }
    if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.host.isNullOrEmpty()) return null
    // A user:token would be saved and shown on screen.
    if (parsed.rawUserInfo != null || parsed.query != null || parsed.fragment != null) return null
    return if (u.endsWith("/")) u else "$u/"
}

/**
 * How a source reads on screen: `github.com/owner/repo` for raw GitHub addresses, with the branch
 * (and any folder) after it unless that is just `main`; else host and path.
 */
fun sourceName(base: String): String {
    val rest = base.substringAfter("://").trimEnd('/')
    val raw = "raw.githubusercontent.com/"
    if (!rest.startsWith(raw)) return rest
    val parts = rest.removePrefix(raw).split('/')
    val where = parts.drop(2).joinToString("/")
    return "github.com/" + parts.take(2).joinToString("/") + if (where == "main" || where.isEmpty()) "" else " ($where)"
}

private const val MAX_BYTES = 2 shl 20 // 2 MiB; the index is ~60 KB
private const val MAX_MILLIS = 30_000L

/**
 * Plain GET with short timeouts, so a slow network falls back to the cache rather than hanging.
 * `https` only, where it ends up too: a redirect to anything else fails. A server that sends a
 * byte every few seconds still gives up after [MAX_MILLIS].
 */
internal suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
    require(url.startsWith("https://", ignoreCase = true)) { "not https: $url" }
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 10_000
    val start = System.nanoTime()
    try {
        check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        check(c.url.protocol.equals("https", ignoreCase = true)) { "redirected off https" }
        check(c.contentLengthLong <= MAX_BYTES) { "too large" }
        c.inputStream.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf).takeIf { it >= 0 } ?: break
                out.write(buf, 0, n)
                check(out.size() <= MAX_BYTES) { "too large" }
                check(System.nanoTime() - start <= MAX_MILLIS * 1_000_000) { "too slow" }
            }
            out.toByteArray()
        }
    } finally {
        c.disconnect()
    }
}

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** Real lists nest about four deep; org.json parses recursively, so a deeply nested file would overflow the stack. */
private const val MAX_DEPTH = 32

internal fun jsonObject(text: String): JSONObject {
    if (depth(text) > MAX_DEPTH) throw org.json.JSONException("nested deeper than $MAX_DEPTH")
    return JSONObject(text)
}

/** Deepest `{`/`[` nesting outside strings. */
internal fun depth(text: String): Int {
    var depth = 0
    var max = 0
    var inString = false
    var escaped = false
    for (c in text) {
        when {
            escaped -> escaped = false
            inString -> when (c) {
                '\\' -> escaped = true
                '"' -> inString = false
            }
            c == '"' -> inString = true
            c == '{' || c == '[' -> { depth++; if (depth > max) max = depth }
            c == '}' || c == ']' -> depth--
        }
    }
    return max
}

internal const val NAME_MAX = 80
internal const val NOTE_MAX = 300

/**
 * Fetched text as it may be shown: no control characters, no bidi controls that would make it
 * read in another order ("\u202Egnp.exe"), no line breaks of Unicode's own, at most [max]
 * characters, trimmed.
 */
internal fun plainText(s: String, max: Int): String {
    val clean = s.filterNot { Character.getType(it) == Character.CONTROL.toInt() || it in BIDI || it == '\u2028' || it == '\u2029' }.trim()
    if (clean.length <= max) return clean
    // Never half a surrogate pair at the cut.
    val end = if (Character.isHighSurrogate(clean[max - 1])) max - 1 else max
    return clean.substring(0, end).trimEnd()
}

private val BIDI = setOf('\u061C', '\u200E', '\u200F') + ('\u202A'..'\u202E') + ('\u2066'..'\u2069')

/** [f] over each element, leaving out any that isn't an object or that [f] can't read. */
internal fun <T : Any> JSONArray.objectsOrSkip(f: (JSONObject) -> T?): List<T> = (0 until length()).mapNotNull {
    try {
        f(getJSONObject(it))
    } catch (_: org.json.JSONException) {
        null
    }
}
private fun JSONArray.strings() = (0 until length()).map { getString(it) }
internal fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k)
private fun JSONObject.optIntOrNull(k: String) = if (isNull(k)) null else optInt(k)
