// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import android.content.Context
import app.tellyscope.adb.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// A launcher downloaded from its developer's GitHub releases: the GitHub build only. The Play build
// downloads no apps; it checks a file the user picked (AndroidLauncherPick).

/** GitHub's API for [releases]' latest release: `https://github.com/<owner>/<repo>/releases` as the list gives it. */
private fun latestApi(releases: String) =
    "https://api.github.com/repos/" + releases.removePrefix("https://github.com/").removeSuffix("/releases") + "/releases/latest"

/**
 * The latest release from GitHub's API, or null unless it is a full release on [launcher]'s own
 * repo with exactly one APK, served from that release: the one named [KnownLauncher.apk], when the list names one.
 */
fun parseRelease(json: String, launcher: KnownLauncher): LauncherRelease? = runCatching {
    val r = JSONObject(json)
    val tag = r.getString("tag_name")
    val assets = r.getJSONArray("assets")
    val apk = (0 until assets.length()).map(assets::getJSONObject).singleOrNull { isApk(it.getString("name"), launcher.apk) }
    val url = apk?.getString("browser_download_url")
    val size = apk?.getLong("size") ?: 0L
    LauncherRelease(tag, r.getString("html_url"), url.orEmpty(), size).takeIf {
        !r.optBoolean("draft") && !r.optBoolean("prerelease") && it.valid(launcher)
    }
}.getOrNull()

/** On [launcher]'s own repo, its one APK served from that release, and about the right size. */
private fun LauncherRelease.valid(launcher: KnownLauncher): Boolean {
    val releases = launcher.releases ?: return false
    return page == "$releases/tag/$version" && apk.startsWith("$releases/download/$version/") && size in 1..launcher.maxBytes
}

/** The tag github.com's `releases/latest` on [releases] redirects to, from its Location; null for anywhere else. */
fun latestTag(location: String?, releases: String): String? =
    location?.removePrefix("$releases/tag/")?.takeIf { it != location && it.isNotEmpty() && '/' !in it && '?' !in it }

/** An asset that is the release's APK: any `.apk`, or only [name] when the list names one. */
private fun isApk(asset: String, name: String?) = if (name != null) asset == name else asset.endsWith(".apk")

/**
 * The release page's one APK, from its asset list's links: the full URL, or null unless there is exactly one
 * (named [name], when the list names one).
 */
fun pageApk(html: String, tag: String, releases: String, name: String? = null): String? {
    val path = releases.removePrefix("https://github.com") + "/download/$tag/"
    return Regex("href=\"(${Regex.escape(path)}[^\"/?#]+\\.apk)\"").findAll(html).map { it.groupValues[1] }.distinct()
        .filter { isApk(it.substringAfterLast('/'), name) }.singleOrNull()?.let { "https://github.com$it" }
}

class AndroidLauncherDownload(context: Context) : AndroidLauncherPick(context), LauncherDownload {
    /**
     * From GitHub's API, or, when that is rate-limited, from the release page it links to, which isn't.
     * Throws [RateLimited] only when the page didn't answer either.
     */
    override suspend fun latest(launcher: KnownLauncher): LauncherRelease = withContext(Dispatchers.IO) {
        val releases = launcher.releases ?: throw IOException("no releases page for ${launcher.pkg}")
        val limited = try {
            return@withContext parseRelease(api(releases), launcher) ?: throw IOException("no release of ${launcher.pkg}")
        } catch (e: RateLimited) {
            e
        }
        runCatching { fromPage(launcher, releases) }.getOrNull() ?: throw limited
    }

    private fun api(releases: String): String {
        val c = URL(latestApi(releases)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            val code = c.responseCode
            if ((code == 403 || code == 429) && c.getHeaderField("x-ratelimit-remaining") == "0") {
                throw RateLimited(c.getHeaderField("x-ratelimit-reset")?.toLongOrNull()?.times(1000))
            }
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.use { it.readBytes() }.decodeToString()
        } finally {
            c.disconnect()
        }
    }

    /** `releases/latest` redirects to the tag, whose asset list names the APK; its size comes from the download's own length. */
    private suspend fun fromPage(launcher: KnownLauncher, releases: String): LauncherRelease? {
        val c = URL("$releases/latest").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        val tag = try {
            latestTag(c.takeIf { it.responseCode in 301..302 }?.getHeaderField("Location"), releases)
        } finally {
            c.disconnect()
        } ?: return null
        val apk = pageApk(download("$releases/expanded_assets/$tag").decodeToString(), tag, releases, launcher.apk) ?: return null
        val head = URL(apk).openConnection() as HttpURLConnection
        head.requestMethod = "HEAD"
        head.connectTimeout = 10_000
        head.readTimeout = 10_000
        val size = try {
            head.contentLengthLong.takeIf { head.responseCode == 200 } ?: return null
        } finally {
            head.disconnect()
        }
        return LauncherRelease(tag, "$releases/tag/$tag", apk, size).takeIf { it.valid(launcher) }
    }

    override suspend fun fetch(launcher: KnownLauncher, release: LauncherRelease, onProgress: (Progress) -> Unit): PickedApp = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, apkName(launcher))
        val c = URL(release.apk).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf).takeIf { it >= 0 } ?: break
                        done += n
                        if (done > release.size) throw IOException("larger than the release says")
                        out.write(buf, 0, n)
                        onProgress(Progress(done, release.size))
                    }
                    if (done != release.size) throw IOException("download cut short")
                }
            }
        } finally {
            c.disconnect()
        }
        read(file, launcher)
    }
}
