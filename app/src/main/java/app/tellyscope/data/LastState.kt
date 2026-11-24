// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** A TV's app list and debloat list as last read, shown the moment its page opens while fresh reads replace them. */
data class LastState(val apps: List<AppInfo>, val lists: Suggestions?)

/**
 * Each TV's [LastState], one JSON file per [historyKey] under [dir]. Reasons are saved as the text
 * they read now, so a file never names a string resource another build has moved. A damaged or
 * older file loads as nothing, and the TV is read as if it were new.
 */
class LastStateBook(private val dir: File, private val text: (UiText) -> String) {

    @Synchronized
    fun load(tv: String): LastState? = runCatching {
        val file = file(tv).takeIf { it.exists() } ?: return null
        val o = JSONObject(file.readText())
        if (o.optInt("v") != VERSION) return null
        LastState(o.getJSONArray("apps").map(::app), o.optJSONObject("lists")?.let(::lists))
    }.getOrNull()

    /** Saves [apps], and [lists] unless it's null or [Suggestions.Unavailable]: then the lists saved before stay. */
    @Synchronized
    fun save(tv: String, apps: List<AppInfo>, lists: Suggestions?) {
        val keep = lists?.takeUnless { it is Suggestions.Unavailable } ?: load(tv)?.lists
        val o = JSONObject().put("v", VERSION).put("apps", JSONArray(apps.map(::appJson)))
        keep?.let { o.put("lists", listsJson(it)) }
        dir.mkdirs()
        val file = file(tv)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Forget: the TV keeps nothing. */
    @Synchronized
    fun forget(tv: String) {
        file(tv).delete()
    }

    /** A new list source: every TV's lists came from the old one, so only the apps stay. */
    @Synchronized
    fun dropLists() {
        dir.listFiles { f -> f.name.endsWith(SUFFIX) }?.forEach { f ->
            runCatching {
                val o = JSONObject(f.readText())
                if (o.has("lists")) {
                    o.remove("lists")
                    f.writeText(o.toString())
                }
            }.onFailure { f.delete() }
        }
    }

    private fun file(tv: String) = File(dir, tv.replace(Regex("[^A-Za-z0-9._-]"), "_") + SUFFIX)

    private fun appJson(a: AppInfo) = JSONObject()
        .put("pkg", a.packageName).put("label", a.label).put("color", a.iconColor).put("system", a.system)
        .put("enabled", a.enabled).put("protected", a.protectedReason?.let(text)).put("version", a.version)
        .put("icon", a.icon?.let { Base64.getEncoder().encodeToString(it) }).put("accent", a.iconAccent)
        .put("removable", a.removable).put("home", a.home).put("listedLauncher", a.listedLauncher)
        .put("homeInstead", a.homeInstead).put("homeFirst", a.homeFirst).put("answersHome", a.answersHome)
        .put("builtInLauncher", a.builtInLauncher).put("homeBack", a.homeBack).put("homeService", a.homeService)
        .put("homeServiceOff", a.homeServiceOff).put("homeServiceNote", a.homeServiceNote).put("listKeep", a.listKeep)
        .put("warn", a.warn).put("launchable", a.launchable).put("tvHome", a.tvHome).put("disabledByTv", a.disabledByTv)

    private fun app(o: JSONObject) = AppInfo(
        packageName = o.getString("pkg"),
        label = o.getString("label"),
        iconColor = o.long("color"),
        system = o.getBoolean("system"),
        enabled = o.getBoolean("enabled"),
        protectedReason = o.string("protected")?.let(UiText::Raw),
        version = o.getString("version"),
        icon = o.string("icon")?.let { Base64.getDecoder().decode(it) },
        iconAccent = o.long("accent"),
        removable = o.optBoolean("removable"),
        home = o.optBoolean("home"),
        listedLauncher = o.optBoolean("listedLauncher"),
        homeInstead = o.string("homeInstead"),
        homeFirst = o.optBoolean("homeFirst"),
        answersHome = o.optBoolean("answersHome"),
        builtInLauncher = o.optBoolean("builtInLauncher"),
        homeBack = o.string("homeBack"),
        homeService = o.optBoolean("homeService"),
        homeServiceOff = o.optBoolean("homeServiceOff"),
        homeServiceNote = o.optBoolean("homeServiceNote"),
        listKeep = o.optBoolean("listKeep"),
        warn = o.string("warn"),
        launchable = o.optBoolean("launchable"),
        tvHome = o.optBoolean("tvHome", true),
        disabledByTv = o.optBoolean("disabledByTv"),
    )

    private fun listsJson(s: Suggestions): JSONObject = when (s) {
        is Suggestions.Unavailable -> JSONObject().put("kind", "unavailable").put("devices", entries(s.devices))
        is Suggestions.NoList -> JSONObject().put("kind", "none").put("commit", s.commit).put("devices", entries(s.devices))
        is Suggestions.Ready -> JSONObject().put("kind", "ready").put("commit", s.commit).put("sdk", s.sdk)
            .put("devices", entries(s.devices)).put("byMaker", s.byMaker)
            .put("list", JSONObject().put("id", s.list.id).put("name", s.list.name).put("testedOs", s.list.testedOs)
                .put("sdk", s.list.sdk).put("keep", JSONArray(s.list.keep.toList()))
                .put("groups", JSONArray(s.list.groups.map { g -> JSONObject().put("title", g.title).put("packages", JSONArray(g.packages.map { p -> JSONObject().put("pkg", p.pkg).put("note", p.note).put("optional", p.optional) })) })))
            .put("groups", JSONArray(s.groups.map { g -> JSONObject().put("title", g.title).put("packages", JSONArray(g.packages.map(::suggestedJson))) }))
            .put("hidden", JSONArray(s.hidden.map(::suggestedJson)))
    }

    private fun lists(o: JSONObject): Suggestions? = when (o.getString("kind")) {
        "unavailable" -> Suggestions.Unavailable(o.getJSONArray("devices").map(::entry))
        "none" -> Suggestions.NoList(o.string("commit"), o.getJSONArray("devices").map(::entry))
        "ready" -> {
            val l = o.getJSONObject("list")
            Suggestions.Ready(
                commit = o.string("commit"),
                list = DeviceList(
                    l.getString("id"), l.getString("name"), l.string("testedOs"), l.int("sdk"),
                    l.getJSONArray("keep").strings().toSet(),
                    l.getJSONArray("groups").map { g -> ListGroup(g.getString("title"), g.getJSONArray("packages").map { p -> ListPackage(p.getString("pkg"), p.string("note"), p.getBoolean("optional")) }) },
                ),
                sdk = o.int("sdk"),
                groups = o.getJSONArray("groups").map { g -> SuggestedGroup(g.getString("title"), g.getJSONArray("packages").map(::suggested)) },
                devices = o.getJSONArray("devices").map(::entry),
                byMaker = o.optBoolean("byMaker"),
                hidden = o.getJSONArray("hidden").map(::suggested),
            )
        }
        else -> null
    }

    private fun suggestedJson(p: Suggested) = JSONObject()
        .put("pkg", p.pkg).put("label", p.label).put("note", p.note).put("optional", p.optional).put("ticked", p.ticked).put("warn", p.warn)

    private fun suggested(o: JSONObject) =
        Suggested(o.getString("pkg"), o.getString("label"), o.string("note"), o.getBoolean("optional"), o.getBoolean("ticked"), o.string("warn"))

    private fun entries(list: List<ListEntry>) = JSONArray(
        list.map { e ->
            JSONObject().put("id", e.id).put("name", e.name).put("sdk", e.sdk).put("file", e.file).put("sha256", e.sha256)
                .put("match", e.match?.let { m -> JSONObject(m.mapValues { JSONArray(it.value) }) })
        },
    )

    private fun entry(o: JSONObject) = ListEntry(
        o.getString("id"), o.getString("name"),
        o.optJSONObject("match")?.let { m -> m.keys().asSequence().associateWith { m.getJSONArray(it).strings() } },
        o.int("sdk"), o.getString("file"), o.getString("sha256"),
    )

    private companion object {
        const val VERSION = 1
        const val SUFFIX = ".last.json"

        // org.json leaves a null value out, so a missing key reads as null.
        fun JSONObject.string(k: String): String? = if (isNull(k)) null else getString(k)
        fun JSONObject.long(k: String): Long? = if (isNull(k)) null else getLong(k)
        fun JSONObject.int(k: String): Int? = if (isNull(k)) null else getInt(k)
        fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }
        fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
    }
}
