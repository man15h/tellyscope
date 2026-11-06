// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory

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
