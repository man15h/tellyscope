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

const val PROJECTIVY = "com.spocky.projengmenu"

/** Where Projectivy's developer publishes its APKs. Not open source: the repo holds only releases and themes. */
const val PROJECTIVY_RELEASES = "https://github.com/spocky/miproja1/releases"
private const val PROJECTIVY_LATEST = "https://api.github.com/repos/spocky/miproja1/releases/latest"

/**
 * SHA-256 of the certificate Projectivy's GitHub APKs are signed with (APK Signature Scheme v2),
 * the same on 4.70 and 4.72. A download signed with anything else is never sent to the TV.
 */
val PROJECTIVY_SIGNERS = setOf("f6697bf4082ee97511e4de07863193884a015b7ab5860430321bda1042b0aadd")

/** Projectivy is about 11 MB; anything far past that isn't it. */
private const val MAX_APK_BYTES = 64L shl 20

/** Projectivy's latest release: [page] is shown before anything downloads, [apk] is its one APK. */
data class LauncherRelease(val version: String, val page: String, val apk: String, val size: Long)

/**
 * The latest release from GitHub's API, or null unless it is a full release on Projectivy's own
 * repo with exactly one APK, served from that release.
 */
fun parseRelease(json: String): LauncherRelease? = runCatching {
    val r = JSONObject(json)
    val tag = r.getString("tag_name")
    val assets = r.getJSONArray("assets")
    val apk = (0 until assets.length()).map(assets::getJSONObject).singleOrNull { it.getString("name").endsWith(".apk") }
    val url = apk?.getString("browser_download_url")
    val size = apk?.getLong("size") ?: 0L
    LauncherRelease(tag, r.getString("html_url"), url.orEmpty(), size).takeIf {
        !r.optBoolean("draft") && !r.optBoolean("prerelease") && it.valid()
    }
}.getOrNull()

/** On Projectivy's own repo, its one APK served from that release, and about the right size. */
private fun LauncherRelease.valid() =
    page == "$PROJECTIVY_RELEASES/tag/$version" && apk.startsWith("$PROJECTIVY_RELEASES/download/$version/") && size in 1..MAX_APK_BYTES

/** The tag github.com's `releases/latest` redirects to, from its Location; null for anywhere else. */
fun latestTag(location: String?): String? =
    location?.removePrefix("$PROJECTIVY_RELEASES/tag/")?.takeIf { it != location && it.isNotEmpty() && '/' !in it && '?' !in it }

/** The release page's one APK, from its asset list's links: the full URL, or null unless there is exactly one. */
fun pageApk(html: String, tag: String): String? {
    val path = PROJECTIVY_RELEASES.removePrefix("https://github.com") + "/download/$tag/"
    return Regex("href=\"(${Regex.escape(path)}[^\"/?#]+\\.apk)\"").findAll(html).map { it.groupValues[1] }.distinct()
        .singleOrNull()?.let { "https://github.com$it" }
}

/**
 * GitHub's API turned the lookup down: it allows 60 an hour per network without a login. [until] is
 * when it takes them again, epoch millis, when it said.
 */
class RateLimited(val until: Long?) : IOException("rate-limited")

/** Projectivy, signed only by its developer: [signers] are the SHA-256s of the APK's certificates. */
fun isProjectivy(packageName: String, signers: Set<String>) =
    packageName == PROJECTIVY && signers.isNotEmpty() && PROJECTIVY_SIGNERS.containsAll(signers)

/** The download isn't Projectivy, or isn't signed by its developer. */
class NotProjectivy : IOException("not Projectivy")

/** Fire OS's way to Projectivy: there's no Play Store, so Tellyscope fetches it from its developer. Faked in tests. */
interface LauncherDownload {
    /** Throws on no network or a release that doesn't parse. */
    suspend fun latest(): LauncherRelease

    /** Downloads [release]'s APK and checks it. Throws [NotProjectivy], or IOException on a failed download. */
    suspend fun fetch(release: LauncherRelease, onProgress: (Progress) -> Unit): PickedApp

    /** A file the user downloaded and picked, checked the same way. Throws [NotProjectivy], or IOException when it can't be read. */
    suspend fun open(uri: Uri): PickedApp
}

class AndroidLauncherDownload(context: Context) : LauncherDownload {
    private val ctx = context.applicationContext
    private val dir get() = File(ctx.cacheDir, "launcher")

    /**
     * From GitHub's API, or, when that is rate-limited, from the release page it links to, which isn't.
     * Throws [RateLimited] only when the page didn't answer either.
     */
    override suspend fun latest(): LauncherRelease = withContext(Dispatchers.IO) {
        val limited = try {
            return@withContext parseRelease(api()) ?: throw IOException("no Projectivy release")
        } catch (e: RateLimited) {
            e
        }
        runCatching { fromPage() }.getOrNull() ?: throw limited
    }

    private fun api(): String {
        val c = URL(PROJECTIVY_LATEST).openConnection() as HttpURLConnection
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
    private suspend fun fromPage(): LauncherRelease? {
        val c = URL("$PROJECTIVY_RELEASES/latest").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        val tag = try {
            latestTag(c.takeIf { it.responseCode in 301..302 }?.getHeaderField("Location"))
        } finally {
            c.disconnect()
        } ?: return null
        val apk = pageApk(download("$PROJECTIVY_RELEASES/expanded_assets/$tag").decodeToString(), tag) ?: return null
        val head = URL(apk).openConnection() as HttpURLConnection
        head.requestMethod = "HEAD"
        head.connectTimeout = 10_000
        head.readTimeout = 10_000
        val size = try {
            head.contentLengthLong.takeIf { head.responseCode == 200 } ?: return null
        } finally {
            head.disconnect()
        }
        return LauncherRelease(tag, "$PROJECTIVY_RELEASES/tag/$tag", apk, size).takeIf { it.valid() }
    }

    override suspend fun fetch(release: LauncherRelease, onProgress: (Progress) -> Unit): PickedApp = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "projectivy.apk")
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
        read(file)
    }

    override suspend fun open(uri: Uri): PickedApp = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "projectivy.apk")
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("can't read $uri")
        input.use { s ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    ensureActive()
                    val n = s.read(buf).takeIf { it >= 0 } ?: break
                    done += n
                    // Far past Projectivy's size: some other file, never read whole.
                    if (done > MAX_APK_BYTES) throw NotProjectivy()
                    out.write(buf, 0, n)
                }
            }
        }
        read(file)
    }

    /** The package manager checks the APK's signature while it reads the certificates. */
    private fun read(file: File): PickedApp {
        val pm = ctx.packageManager
        val pkg = if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION") // the flags overload is API 33+
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        } ?: throw NotProjectivy()
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            // A rotated key lists its past certificates first; the current one signs.
            pkg.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners.toList() else it.signingCertificateHistory.takeLast(1) }
        } else {
            @Suppress("DEPRECATION")
            pkg.signatures?.toList()
        }
        val signers = certs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
        if (!isProjectivy(pkg.packageName, signers)) throw NotProjectivy()
        return PickedApp(
            files = listOf(file),
            info = ApkInfo(pkg.packageName, PackageInfoCompat.getLongVersionCode(pkg), pkg.versionName),
            label = "Projectivy Launcher",
        )
    }
}

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
