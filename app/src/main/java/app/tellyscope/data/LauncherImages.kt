// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * A launcher's icon ([KnownLauncher.icon]), linked from its developer's own page rather than kept in
 * this repository, so the art stays theirs. Fetched only for launchers on `launchers.json`, over [download]
 * (`https` only, size and time capped), and cached under [dir] by the address, so each is fetched once.
 */
class LauncherImages(private val dir: File, private val fetch: suspend (String) -> ByteArray = ::download) {
    /** The image at [url], cached or fetched; null when it can't be had or isn't a picture [Picture.ok] takes. */
    suspend fun get(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val cache = File(dir, sha256(url))
        cache.takeIf { it.exists() }?.readBytes()?.takeIf(Picture::ok)
            ?: try {
                fetch(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }?.takeIf(Picture::ok)?.also { dir.mkdirs(); cache.writeBytes(it) }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * The checks a launcher's image passes before it is cached or decoded: a PNG, JPEG or WebP whose sides, read from
 * its header without decoding the pixels, are 1 to [MAX_SIDE]. It is decoded scaled down to about [SHOWN] a side.
 */
object Picture {
    /** A developer's 1920 × 1080 screenshot is a few hundred KB. */
    const val MAX_BYTES = 2 shl 20
    const val MAX_SIDE = 4096
    /** The picker's screenshot is at most a phone's width. */
    const val SHOWN = 1280

    private val TYPES = setOf("image/png", "image/jpeg", "image/webp")

    fun ok(b: ByteArray): Boolean = bounds(b) != null

    /** [b] as a bitmap, sampled down by powers of two until its longer side is at most [SHOWN]; null unless [ok]. */
    fun decode(b: ByteArray): Bitmap? {
        val (w, h) = bounds(b) ?: return null
        var sample = 1
        while (maxOf(w, h) / sample > SHOWN) sample *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun bounds(b: ByteArray): Pair<Int, Int>? {
        if (b.isEmpty() || b.size > MAX_BYTES) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        return (o.outWidth to o.outHeight).takeIf { o.outMimeType in TYPES && it.first in 1..MAX_SIDE && it.second in 1..MAX_SIDE }
    }
}
