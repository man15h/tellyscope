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
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A launcher's latest release: [page] is shown before anything downloads, [apk] is its one APK. */
data class LauncherRelease(val version: String, val page: String, val apk: String, val size: Long)

/**
 * The file [launcher] is saved as: its list name with spaces as `-` and only `[A-Za-z0-9._-]` kept,
 * so no `/` or `..` path, at most 64 characters; its package name when nothing is left.
 */
fun apkName(launcher: KnownLauncher): String =
    launcher.name.replace(' ', '-').filter { it.isLetterOrDigit() && it.code < 128 || it in "._-" }.take(64)
        .ifEmpty { launcher.pkg } + ".apk"

/**
 * GitHub's API turned the lookup down: it allows 60 an hour per network without a login. [until] is
 * when it takes them again, epoch millis, when it said.
 */
class RateLimited(val until: Long?) : IOException("rate-limited")

/** The APK isn't the launcher it should be ([KnownLauncher.accepts]): another package, or not signed by its developer. */
class WrongApk : IOException("not the launcher")

/** A launcher's APK the user downloaded and picked, checked before anything reaches the TV. Faked in tests. */
interface LauncherPick {
    /** Throws [WrongApk], or IOException when it can't be read. */
    suspend fun open(uri: Uri, launcher: KnownLauncher): PickedApp
}

/**
 * Fire OS's way to its launcher: there's no Play Store, so Tellyscope fetches it from its developer's
 * [KnownLauncher.releases] (`AndroidLauncherDownload`, GitHub build only). Faked in tests.
 */
interface LauncherDownload : LauncherPick {
    /** Throws on no network, no [KnownLauncher.releases], or a release that doesn't parse. */
    suspend fun latest(launcher: KnownLauncher): LauncherRelease

    /** Downloads [release]'s APK and checks it. Throws [WrongApk], or IOException on a failed download. */
    suspend fun fetch(launcher: KnownLauncher, release: LauncherRelease, onProgress: (Progress) -> Unit): PickedApp
}

/** The pick and the check, which both builds have; the GitHub build's [LauncherDownload] adds the download. */
open class AndroidLauncherPick(context: Context) : LauncherPick {
    protected val ctx: Context = context.applicationContext
    protected val dir get() = File(ctx.cacheDir, "launcher")

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
    protected fun read(file: File, launcher: KnownLauncher): PickedApp {
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
