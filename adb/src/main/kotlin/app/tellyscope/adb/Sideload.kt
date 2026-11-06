// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.CancellationException
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What the phone read from the APK before sending it. */
data class ApkInfo(val packageName: String, val versionCode: Long, val versionName: String?)

data class InstalledVersion(val versionCode: Long, val versionName: String?)

enum class InstallKind { NEW, UPDATE, REINSTALL, DOWNGRADE }

fun installKind(apk: ApkInfo, installed: InstalledVersion?): InstallKind = when {
    installed == null -> InstallKind.NEW
    apk.versionCode > installed.versionCode -> InstallKind.UPDATE
    apk.versionCode == installed.versionCode -> InstallKind.REINSTALL
    else -> InstallKind.DOWNGRADE
}

/** Why the TV wouldn't install. The app turns each one into a sentence. */
enum class InstallRefusal {
    /** Same package, signed by someone else: only an uninstall would let it in, and we never uninstall. */
    SIGNATURE,
    DOWNGRADE,
    NO_SPACE,
    /** A policy, a verifier or the TV's owner settings blocked it. */
    TV_SAID_NO,
    /** The app needs a newer Android than the TV has. */
    TV_TOO_OLD,
    /** Built for a different processor, e.g. an arm64-only app on a 32-bit TV. */
    WRONG_CPU,
    /** Targets an Android so old that the TV refuses it. */
    APP_TOO_OLD,
    /** Needs hardware the TV doesn't have (a touchscreen, a camera). */
    MISSING_FEATURE,
    /** A split app with a part missing. */
    MISSING_PARTS,
    TEST_ONLY,
    /** Another installed app already claims one of its permissions or providers. */
    CONFLICT,
    /** Not an APK, or a damaged one. */
    BROKEN,
    UNKNOWN,
}

sealed interface InstallResult {
    data object Done : InstallResult
    /** [detail] is the TV's own words, for the "details" line and bug reports. */
    data class Refused(val reason: InstallRefusal, val detail: String) : InstallResult
}

/**
 * Installs APKs from the phone. Every install replaces in place (`-r`); a downgrade happens only
 * when the caller passes `allowDowngrade`, which the UI sets only after the user asked for it.
 * Also copies an installed app's APKs back to the phone.
 */
class Sideload(private val session: AdbSession) {

    /** The version on the TV, or null when it isn't installed. */
    suspend fun installed(pkg: String): InstalledVersion? {
        require(Actions.PACKAGE.matches(pkg)) { "not a package name: $pkg" }
        return parseInstalled(session.shell("dumpsys package $pkg").output, pkg)
    }

    /**
     * Installs [apks], the base first for a split app. One APK streams with byte progress; a split
     * set goes through Kadb's install session, which reports nothing until it finishes, so
     * [onProgress] then only sees an unknown total.
     */
    suspend fun install(
        apks: List<File>,
        apk: ApkInfo,
        allowDowngrade: Boolean,
        onProgress: (Progress) -> Unit = {},
    ): InstallResult {
        require(apks.isNotEmpty()) { "nothing to install" }
        val kind = installKind(apk, installed(apk.packageName))
        if (kind == InstallKind.DOWNGRADE && !allowDowngrade) {
            return InstallResult.Refused(InstallRefusal.DOWNGRADE, "the TV has a newer version")
        }
        val options = if (kind == InstallKind.DOWNGRADE) arrayOf("-r", "-d") else arrayOf("-r")
        return try {
            runTransfer { stopped ->
                if (apks.size == 1) {
                    val f = apks.single()
                    f.source().use { src ->
                        session.install(src.counting(f.length(), stopped, onProgress), f.length(), *options)
                    }
                } else {
                    onProgress(Progress(0, null))
                    session.installMultiple(apks, *options)
                }
            }
            InstallResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kadb throws IllegalStateException or IOException with "Failure [INSTALL_...]" in it.
            val out = e.message.orEmpty()
            InstallResult.Refused(refusal(out), out.substringAfter("Install failed: ").trim())
        }
    }

    /** Where [pkg]'s APKs are on the TV: one path, or several for a split app. Empty when it isn't installed. */
    suspend fun apks(pkg: String): List<String> {
        require(Actions.PACKAGE.matches(pkg)) { "not a package name: $pkg" }
        return session.shell("pm path $pkg 2>/dev/null").output.lineSequence()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toList()
    }

    /**
     * Copies [paths], from [apks], into [sink]: the APK itself, or a split app's parts zipped flat as
     * an .apks bundle, which [ApkBundle.unpack] reads back for Install. On cancel the caller deletes
     * what it was writing to.
     */
    suspend fun pullApks(paths: List<String>, sink: Sink, onProgress: (Progress) -> Unit = {}) {
        require(paths.isNotEmpty()) { "nothing to copy" }
        val sizes = session.shell("stat -c %s " + paths.joinToString(" ") { shellQuote(it) }).output
            .lines().mapNotNull { it.trim().toLongOrNull() }
        val total = sizes.takeIf { it.size == paths.size }?.sum()
        runTransfer { stopped ->
            if (paths.size == 1) return@runTransfer session.pull(sink.counting(total, stopped, onProgress), paths.single())
            val out = sink.buffer()
            // APKs are zips already: storing them as they are is as small, and far quicker on the phone.
            val zip = ZipOutputStream(out.outputStream()).apply { setLevel(Deflater.NO_COMPRESSION) }
            // One counter across every part, and a close that only flushes: closing would end the zip.
            val part = object : ForwardingSink(zip.sink()) {
                override fun close() = flush()
            }.counting(total, stopped, onProgress)
            paths.forEach { p ->
                zip.putNextEntry(ZipEntry(p.substringAfterLast('/')))
                session.pull(part, p)
                part.flush()
                zip.closeEntry()
            }
            zip.finish()
            out.flush()
        }
    }

    companion object {
        /**
         * The version from `dumpsys package <pkg>`. Only the active entry counts: an updated
         * system app also lists its factory copy under "Hidden system packages".
         */
        fun parseInstalled(dumpsys: String, pkg: String): InstalledVersion? {
            val active = dumpsys.substringBefore("Hidden system packages:")
            val start = active.indexOf("Package [$pkg]")
            if (start < 0) return null
            val block = active.substring(start).lineSequence().drop(1).takeWhile { !it.trimStart().startsWith("Package [") }.joinToString("\n")
            val code = Regex("""versionCode=(\d+)""").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val name = Regex("""versionName=(\S+)""").find(block)?.groupValues?.get(1)
            return InstalledVersion(code, name)
        }

        fun refusal(output: String): InstallRefusal {
            val code = Regex("""INSTALL_[A-Z_]+""").find(output)?.value
                ?: return if ("not enough space" in output.lowercase()) InstallRefusal.NO_SPACE else InstallRefusal.UNKNOWN
            return when (code) {
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
                "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE",
                "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" -> InstallRefusal.SIGNATURE
                "INSTALL_FAILED_VERSION_DOWNGRADE" -> InstallRefusal.DOWNGRADE
                "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> InstallRefusal.NO_SPACE
                "INSTALL_FAILED_USER_RESTRICTED",
                "INSTALL_FAILED_VERIFICATION_FAILURE",
                "INSTALL_FAILED_VERIFICATION_TIMEOUT",
                "INSTALL_FAILED_ABORTED" -> InstallRefusal.TV_SAID_NO
                "INSTALL_FAILED_OLDER_SDK" -> InstallRefusal.TV_TOO_OLD
                "INSTALL_FAILED_NO_MATCHING_ABIS",
                "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" -> InstallRefusal.WRONG_CPU
                "INSTALL_FAILED_DEPRECATED_SDK_VERSION" -> InstallRefusal.APP_TOO_OLD
                "INSTALL_FAILED_MISSING_FEATURE" -> InstallRefusal.MISSING_FEATURE
                "INSTALL_FAILED_MISSING_SPLIT" -> InstallRefusal.MISSING_PARTS
                "INSTALL_FAILED_TEST_ONLY" -> InstallRefusal.TEST_ONLY
                "INSTALL_FAILED_DUPLICATE_PERMISSION",
                "INSTALL_FAILED_CONFLICTING_PROVIDER",
                "INSTALL_FAILED_DUPLICATE_PACKAGE",
                "INSTALL_FAILED_ALREADY_EXISTS" -> InstallRefusal.CONFLICT
                "INSTALL_FAILED_INVALID_APK", "INSTALL_FAILED_INVALID_URI" -> InstallRefusal.BROKEN
                else -> if (code.startsWith("INSTALL_PARSE_FAILED")) InstallRefusal.BROKEN else InstallRefusal.UNKNOWN
            }
        }
    }
}
