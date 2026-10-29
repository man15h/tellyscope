// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * The checks a fetched logo passes before it is cached or decoded. A PNG's width and height sit in
 * its header, so a small file that would decode to a huge bitmap is refused unread.
 */
object Png {
    /** The largest logo today is about 9 KB. */
    const val MAX_BYTES = 64 * 1024

    /** Logos are drawn at four times their size in dp, about 280 × 80 px. */
    const val MAX_SIDE = 1024

    private val MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val IHDR = "IHDR".toByteArray()

    /** Width and height from the header, or null when [b] doesn't start like a PNG. */
    fun size(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24 || !b.copyOfRange(0, 8).contentEquals(MAGIC) || !b.copyOfRange(12, 16).contentEquals(IHDR)) return null
        return int(b, 16) to int(b, 20)
    }

    /** A PNG no larger than [MAX_BYTES] whose sides are 1 to [MAX_SIDE] pixels. */
    fun ok(b: ByteArray): Boolean = b.size <= MAX_BYTES && fits(size(b))

    /**
     * [b] as a bitmap after [ok], with the size the decoder itself reads (`inJustDecodeBounds`)
     * checked again before the pixels are; null for anything that fails either.
     */
    fun decode(b: ByteArray): Bitmap? {
        if (!ok(b)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
        if (bounds.outMimeType != "image/png" || !fits(bounds.outWidth to bounds.outHeight)) return null
        return BitmapFactory.decodeByteArray(b, 0, b.size)
    }

    fun fits(size: Pair<Int, Int>?): Boolean = size != null && size.first in 1..MAX_SIDE && size.second in 1..MAX_SIDE

    private fun int(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)
}
