// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.adb.AppLabel
import app.tellyscope.adb.Parsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/**
 * What the app list shows for one package. [icon] is a PNG; null means a letter avatar, in
 * [tileColor] and [tileAccent] for a banner-only app.
 */
class AppName(val label: String, val icon: ByteArray?, val tileColor: Long? = null, val tileAccent: Long? = null)

/**
 * Real app names and icons per TV. The TV's own labels come from `describe` (the label helper) and
 * are cached in [dir], one file per TV, so the helper only runs again for new or updated apps. When
 * the helper can't run, `labels.json` ([SharedLists.labels]) and then a humanised
 * package id stand in, with no icon, and that TV isn't asked again until the app restarts. Each
 * TV's file is read once per launch, off the main thread, and kept: every list after that gets the
 * same icon arrays, so the screen doesn't decode them again.
 */
class AppNames(private val dir: File) {
    /** One helper run at a time; a second caller then finds the first one's results cached. */
    private val lock = Mutex()
    private val refused = mutableSetOf<String>()
    /** Asked for and not described (no readable app info): don't run the helper again just for these. */
    private val unanswered = mutableSetOf<Pair<String, String>>()
    /** Asked for in this launch. A cached entry without an icon is asked for once more per launch. */
    private val asked = mutableSetOf<Pair<String, String>>()
    /** [names]' `tv` → its cache, as read from disk once and updated since. */
    private val loaded = mutableMapOf<String, MutableMap<String, AppLabel>>()

    /**
     * [tv] identifies the TV (its [historyKey]); [packages] is `pm list packages --show-versioncode`.
     * [describe] runs the helper on that TV for the packages it's given, null if it couldn't.
     */
    suspend fun names(
        tv: String,
        packages: List<Parsers.Package>,
        describe: suspend (List<String>) -> List<AppLabel>?,
    ): Map<String, AppName> = lock.withLock {
        val file = File(dir, fileName(tv))
        val cache = loaded.getOrPut(tv) {
            withContext(Dispatchers.IO) {
                File(dir, fileName(tv, OLD_CACHE)).delete()
                load(file).toMutableMap()
            }
        }
        val stale = packages.filter { p ->
            val hit = cache[p.name]
            // Without a versionCode (an older pm) a cached label is as good as it gets.
            val outdated = hit == null || (p.versionCode != null && p.versionCode != hit.versionCode)
            // A run where the icon failed once mustn't cost the app its icon until its next update.
            (outdated || (hit?.iconPng == null && hit?.tileColor == null && (tv to p.name) !in asked)) && (tv to p.name) !in unanswered
        }
        if (stale.isNotEmpty() && tv !in refused) {
            val fresh = describe(stale.map { it.name })
            stale.forEach { asked += tv to it.name }
            if (fresh == null) {
                refused += tv
            } else {
                fresh.forEach { cache[it.packageName] = it }
                stale.filter { s -> fresh.none { it.packageName == s.name } }.forEach { unanswered += tv to it.name }
                val labels = cache.values.toList()
                withContext(Dispatchers.IO) { save(file, labels) }
            }
        }
        packages.associate { p ->
            p.name to (cache[p.name]?.let { AppName(named(p.name, it.label), it.iconPng, it.tileColor, it.tileAccent) } ?: AppName(fallback(p.name), null))
        }
    }

    companion object {
        /**
         * `labels.json` first, then a humanised id that keeps the maker: `com.xgimi.assistant` →
         * "XGIMI Assistant", `com.google.android.youtube.tv` → "Youtube".
         */
        fun fallback(pkg: String): String = SharedLists.labels[pkg] ?: humanise(pkg)

        /**
         * [label], unless it's the package or one of its classes, which Android reports for an app
         * with no label of its own (`com.amazon.firehomestarter.FireHomeStarterApplication`).
         */
        fun named(pkg: String, label: String): String =
            if (label == pkg || label.startsWith("$pkg.")) fallback(pkg) else label

        /** What native daemons and system uids are listed as, in place of an app name. */
        const val SYSTEM = "Android system"

        private val GENERIC = setOf("com", "net", "org", "io", "co", "android", "google", "tv", "app", "apps")

        /** Makers whose name isn't simply capitalised. */
        private val BRAND_CASE = mapOf("xgimi" to "XGIMI", "tcl" to "TCL", "lg" to "LG", "nvidia" to "NVIDIA", "hbo" to "HBO", "bbc" to "BBC")

        private fun humanise(pkg: String): String {
            val parts = pkg.split('.').filter { it.isNotEmpty() && it.lowercase() !in GENERIC }
            val name = parts.lastOrNull() ?: return pkg
            // The maker is the first part left; skip it when the name already says it.
            val maker = parts.first().takeIf { parts.size > 1 && !name.lowercase().contains(it.lowercase()) }
            return listOfNotNull(maker, name).joinToString(" ") { BRAND_CASE[it.lowercase()] ?: it.replaceFirstChar(Char::uppercase) }
        }

        /**
         * Version 2 came with real adaptive icons: a version 1 file holds banners in their place at the
         * same versionCode, so it's dropped and every TV is read again once.
         */
        private const val CACHE = ".v2.tsv"
        private const val OLD_CACHE = ".tsv"

        private fun fileName(tv: String, suffix: String = CACHE) = tv.replace(Regex("[^A-Za-z0-9._-]"), "_") + suffix

        /**
         * `package \t versionCode \t label \t base64 PNG \t tile colour \t tile accent`. A damaged
         * line costs that app, not the file. Lines from before the tile columns don't load, so
         * every TV is read again and its squashed banner icons are replaced.
         */
        private fun load(file: File): Map<String, AppLabel> {
            if (!file.exists()) return emptyMap()
            return runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
                val f = line.split('\t')
                if (f.size != 6) return@mapNotNull null
                val version = f[1].toLongOrNull() ?: return@mapNotNull null
                if (f[0].isEmpty() || f[2].isEmpty()) return@mapNotNull null
                val icon = f[3].takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                AppLabel(f[0], f[2], version, icon, f[4].toLongOrNull(), f[5].toLongOrNull())
            }.associateBy { it.packageName }
        }

        private fun save(file: File, labels: Collection<AppLabel>) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(
                labels.joinToString("") { a ->
                    val label = a.label.replace(Regex("[\t\r\n]"), " ")
                    val icon = a.iconPng?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()
                    "${a.packageName}\t${a.versionCode}\t$label\t$icon\t${a.tileColor ?: ""}\t${a.tileAccent ?: ""}\n"
                },
            )
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }
}
