// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.tellyscope.adb.ApkInfo
import app.tellyscope.adb.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A launcher's latest release: [page] is shown before anything downloads, [apk] is its one APK. */
data class LauncherRelease(val version: String, val page: String, val apk: String, val size: Long)

/** GitHub's API for [releases]' latest release: `https://github.com/<owner>/<repo>/releases` as the list gives it. */
private fun latestApi(releases: String) =
    "https://api.github.com/repos/" + releases.removePrefix("https://github.com/").removeSuffix("/releases") + "/releases/latest"

/**
 * The latest release from GitHub's API, or null unless it is a full release on [launcher]'s own
 * repo with exactly one APK, served from that release.
 */
fun parseRelease(json: String, launcher: KnownLauncher): LauncherRelease? = runCatching {
    val r = JSONObject(json)
    val tag = r.getString("tag_name")
    val assets = r.getJSONArray("assets")
    val apk = (0 until assets.length()).map(assets::getJSONObject).singleOrNull { it.getString("name").endsWith(".apk") }
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

/**
 * The file [launcher] is saved as: its list name with spaces as `-` and only `[A-Za-z0-9._-]` kept,
 * so no `/` or `..` path, at most 64 characters; its package name when nothing is left.
 */
fun apkName(launcher: KnownLauncher): String =
    launcher.name.replace(' ', '-').filter { it.isLetterOrDigit() && it.code < 128 || it in "._-" }.take(64)
        .ifEmpty { launcher.pkg } + ".apk"

/** The release page's one APK, from its asset list's links: the full URL, or null unless there is exactly one. */
fun pageApk(html: String, tag: String, releases: String): String? {
    val path = releases.removePrefix("https://github.com") + "/download/$tag/"
    return Regex("href=\"(${Regex.escape(path)}[^\"/?#]+\\.apk)\"").findAll(html).map { it.groupValues[1] }.distinct()
        .singleOrNull()?.let { "https://github.com$it" }
}

/**
 * GitHub's API turned the lookup down: it allows 60 an hour per network without a login. [until] is
 * when it takes them again, epoch millis, when it said.
 */
class RateLimited(val until: Long?) : IOException("rate-limited")

/** The APK isn't the launcher it should be ([KnownLauncher.accepts]): another package, or not signed by its developer. */
class WrongApk : IOException("not the launcher")

/**
 * Fire OS's way to its launcher: there's no Play Store, so Tellyscope fetches it from its developer's
 * [KnownLauncher.releases]. Faked in tests.
 */
interface LauncherDownload {
    /** Throws on no network, no [KnownLauncher.releases], or a release that doesn't parse. */
    suspend fun latest(launcher: KnownLauncher): LauncherRelease

    /** Downloads [release]'s APK and checks it. Throws [WrongApk], or IOException on a failed download. */
    suspend fun fetch(launcher: KnownLauncher, release: LauncherRelease, onProgress: (Progress) -> Unit): PickedApp

    /** A file the user downloaded and picked, checked the same way. Throws [WrongApk], or IOException when it can't be read. */
    suspend fun open(uri: Uri, launcher: KnownLauncher): PickedApp
}

class AndroidLauncherDownload(context: Context) : LauncherDownload {
    private val ctx = context.applicationContext
    private val dir get() = File(ctx.cacheDir, "launcher")

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
        val apk = pageApk(download("$releases/expanded_assets/$tag").decodeToString(), tag, releases) ?: return null
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

    override suspend fun open(uri: Uri, launcher: KnownLauncher): PickedApp = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, apkName(launcher))
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("can't read $uri")
        input.use { s ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    ensureActive()
                    val n = s.read(buf).takeIf { it >= 0 } ?: break
                    done += n
                    // Far past the launcher's size: some other file, never read whole.
                    if (done > launcher.maxBytes) throw WrongApk()
                    out.write(buf, 0, n)
                }
            }
        }
        read(file, launcher)
    }

    /** The package manager checks the APK's signature while it reads the certificates. */
    private fun read(file: File, launcher: KnownLauncher): PickedApp {
        val pm = ctx.packageManager
        val pkg = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION") // the flags overload is API 33+
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        } ?: throw WrongApk()
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            // A rotated key lists its past certificates first; the current one signs.
            pkg.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners.toList() else it.signingCertificateHistory.takeLast(1) }
        } else {
            @Suppress("DEPRECATION")
            pkg.signatures?.toList()
        }
        val signers = certs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
        if (!launcher.accepts(pkg.packageName, signers)) throw WrongApk()
        return PickedApp(
            files = listOf(file),
            info = ApkInfo(pkg.packageName, PackageInfoCompat.getLongVersionCode(pkg), pkg.versionName),
            label = launcher.name,
        )
    }
}

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
