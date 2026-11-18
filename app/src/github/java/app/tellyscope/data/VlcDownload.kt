// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.adb.Progress
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// The player from VideoLAN's own downloads: the GitHub build only. get.videolan.org lists every CPU type's build of
// the latest release, each with a .sha256 beside it, and sends the download itself to a mirror.

private val LISTED = Regex("""href="VLC-Android-([0-9][0-9.]{0,31})-([a-z0-9_-]{1,32})\.apk">[^<]*</a>\s+\S+\s+\S+\s+([0-9]{1,12})\s*$""")
private val SHA256_LINE = Regex("^([0-9a-f]{64})\\b")

/**
 * The build for the first of [abis] that the `last/` listing has, its size as listed; null when it has none of them
 * or one is past [PLAYER]'s size. The sha256 is filled in later, from the file beside it.
 */
fun parseVlcListing(html: String, abis: List<String>): PlayerRelease? {
    val builds = html.lineSequence().mapNotNull { LISTED.find(it)?.groupValues }.associateBy { it[2] }
    val abi = abis.firstOrNull { it in builds } ?: return null
    val (_, version, _, size) = builds.getValue(abi)
    val bytes = size.toLong().takeIf { it in 1..PLAYER.maxBytes } ?: return null
    val page = "$PLAYER_DOWNLOADS$version/"
    return PlayerRelease(version, abi, page, "${page}VLC-Android-$version-$abi.apk", bytes, sha256 = "")
}

/** The hash a `.sha256` file names, as sha256sum writes it; null for anything else. */
fun parseSha256(text: String): String? = SHA256_LINE.find(text.trim())?.groupValues?.get(1)

internal fun latestVlc(abis: List<String>): PlayerRelease {
    val release = parseVlcListing(get("${PLAYER_DOWNLOADS}last/", 64 * 1024).decodeToString(), abis) ?: throw IOException("no VLC for $abis")
    val sha = parseSha256(get("${release.apk}.sha256", 1024).decodeToString()) ?: throw IOException("no sha256 for ${release.apk}")
    return release.copy(sha256 = sha)
}

/** Downloads [release] into [file]; the caller checks VideoLAN's signature. Throws [WrongApk] when the hash differs. */
internal suspend fun fetchVlc(release: PlayerRelease, file: File, onProgress: (Progress) -> Unit) {
    val c = open(release.apk, 30_000)
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        c.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf).takeIf { it >= 0 } ?: break
                    done += n
                    if (done > release.size) throw IOException("larger than the listing says")
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    onProgress(Progress(done, release.size))
                }
                if (done != release.size) throw IOException("download cut short")
            }
        }
    } finally {
        c.disconnect()
    }
    if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256) throw WrongApk()
}

/** [url]'s body, at most [max] bytes. */
private fun get(url: String, max: Int): ByteArray {
    val c = open(url, 10_000)
    try {
        return c.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf).takeIf { it >= 0 } ?: break
                out.write(buf, 0, n)
                if (out.size() > max) throw IOException("too long: $url")
            }
            out.toByteArray()
        }
    } finally {
        c.disconnect()
    }
}

/** A 200 from [url], after any redirects to a mirror, all on https. */
private fun open(url: String, readTimeout: Int): HttpURLConnection {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = readTimeout
    val code = c.responseCode
    if (!c.url.protocol.equals("https", ignoreCase = true)) {
        c.disconnect()
        throw IOException("redirected off https")
    }
    if (code != 200) {
        c.disconnect()
        throw IOException("HTTP $code")
    }
    return c
}
