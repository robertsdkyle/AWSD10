// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif.sticker

import java.io.OutputStream

/**
 * A 256 color palette for GIF stickers. Index 0 is reserved for "transparent".
 *
 * Build it by feeding every frame to a [Builder] first: colors are bucketed to 15 bits, the most
 * used buckets get a palette slot, and anything else maps to the nearest slot. Stickers only use
 * a handful of colors (fill, outline, extrude and their anti-aliased blends) so this is plenty.
 */
class GifPalette private constructor(
    /** 256 RGB colors (no alpha). */
    val colors: IntArray,
    /** 15 bit bucket -> palette index, or -1 when not looked up yet. */
    private val lookup: IntArray,
) {
    /** Palette index for an ARGB pixel. Mostly transparent pixels become index 0. */
    fun indexOf(argb: Int): Int {
        if ((argb ushr 24) < ALPHA_THRESHOLD) return TRANSPARENT_INDEX
        val bucket = bucketOf(argb)
        val cached = lookup[bucket]
        if (cached >= 0) return cached
        val nearest = nearest(argb)
        lookup[bucket] = nearest
        return nearest
    }

    private fun nearest(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        var best = 1
        var bestDistance = Int.MAX_VALUE
        for (i in 1 until colors.size) {
            val c = colors[i]
            val dr = r - ((c shr 16) and 0xff)
            val dg = g - ((c shr 8) and 0xff)
            val db = b - (c and 0xff)
            // weighted a bit towards green, the eye is most sensitive to it
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    class Builder {
        private val counts = IntArray(BUCKETS)
        private val sumR = LongArray(BUCKETS)
        private val sumG = LongArray(BUCKETS)
        private val sumB = LongArray(BUCKETS)

        fun add(pixels: IntArray) {
            for (p in pixels) {
                if ((p ushr 24) < ALPHA_THRESHOLD) continue
                val bucket = bucketOf(p)
                counts[bucket]++
                sumR[bucket] += ((p shr 16) and 0xff).toLong()
                sumG[bucket] += ((p shr 8) and 0xff).toLong()
                sumB[bucket] += (p and 0xff).toLong()
            }
        }

        fun build(): GifPalette {
            val used = (0 until BUCKETS).filter { counts[it] > 0 }.sortedByDescending { counts[it] }.take(MAX_COLORS - 1)
            val colors = IntArray(MAX_COLORS) // unused slots stay black, which is harmless
            val lookup = IntArray(BUCKETS) { -1 }
            used.forEachIndexed { i, bucket ->
                val n = counts[bucket]
                val r = (sumR[bucket] / n).toInt()
                val g = (sumG[bucket] / n).toInt()
                val b = (sumB[bucket] / n).toInt()
                colors[i + 1] = (r shl 16) or (g shl 8) or b
                lookup[bucket] = i + 1
            }
            if (used.isEmpty()) lookup.fill(1) // fully transparent sticker: every opaque lookup goes to slot 1
            return GifPalette(colors, lookup)
        }
    }

    companion object {
        const val TRANSPARENT_INDEX = 0
        const val MAX_COLORS = 256
        /** GIF has on/off transparency; pixels below this alpha are dropped. */
        const val ALPHA_THRESHOLD = 110
        private const val BUCKETS = 1 shl 15

        private fun bucketOf(argb: Int) =
            (((argb shr 19) and 0x1f) shl 10) or (((argb shr 11) and 0x1f) shl 5) or ((argb shr 3) and 0x1f)
    }
}

/**
 * Minimal animated GIF89a writer: one global palette, full-size frames, transparent background
 * (each frame is cleared before the next one), loops forever.
 *
 * Usage: create, call [addFrame] for each frame, then [finish]. Pixels are ARGB ints, row by row.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val palette: GifPalette,
) {
    private val indices = ByteArray(width * height)
    private val lzw = LzwWriter(out)
    private var finished = false

    init {
        require(width in 1..0xffff && height in 1..0xffff)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        // global color table present, 8 bits color resolution, 256 entries (2^(7+1))
        out.write(0x80 or (7 shl 4) or 7)
        out.write(GifPalette.TRANSPARENT_INDEX) // background color index
        out.write(0) // no pixel aspect ratio
        for (c in palette.colors) {
            out.write((c shr 16) and 0xff)
            out.write((c shr 8) and 0xff)
            out.write(c and 0xff)
        }
        // application extension that makes the animation loop forever
        out.write(0x21); out.write(0xff); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1); writeShort(0); out.write(0)
    }

    /** Adds a frame shown for [delayMs] milliseconds (GIF stores hundredths of a second). */
    fun addFrame(argb: IntArray, delayMs: Int) {
        check(!finished)
        require(argb.size == width * height)
        for (i in argb.indices) indices[i] = palette.indexOf(argb[i]).toByte()

        // graphic control extension: clear to background after this frame, index 0 is transparent
        out.write(0x21); out.write(0xf9); out.write(4)
        out.write((2 shl 2) or 1)
        writeShort(((delayMs + 5) / 10).coerceAtLeast(2))
        out.write(GifPalette.TRANSPARENT_INDEX)
        out.write(0)

        // image descriptor covering the whole canvas, no local palette, not interlaced
        out.write(0x2c)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
        out.write(0)

        lzw.write(indices, MIN_CODE_SIZE)
    }

    fun finish() {
        if (finished) return
        finished = true
        out.write(0x3b)
        out.flush()
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
    }

    companion object {
        private const val MIN_CODE_SIZE = 8
    }
}

/** GIF flavored LZW compression, written out in GIF's 255 byte sub-blocks. */
internal class LzwWriter(private val out: OutputStream) {
    private val block = ByteArray(255)
    private var blockSize = 0
    private var bitBuffer = 0
    private var bitCount = 0
    private var codeSize = 0

    // open addressing table: key = (prefix code shl 8) or next index, value = code
    private val keys = IntArray(TABLE_SIZE)
    private val values = IntArray(TABLE_SIZE)

    fun write(data: ByteArray, minCodeSize: Int) {
        out.write(minCodeSize)
        val clear = 1 shl minCodeSize
        val end = clear + 1
        var next = clear + 2
        codeSize = minCodeSize + 1
        keys.fill(-1)
        bitBuffer = 0
        bitCount = 0
        blockSize = 0

        fun emit(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                addByte(bitBuffer and 0xff)
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
            // a decoder grows its code size once its table reaches the current maximum
            if (next > (1 shl codeSize) - 1 && codeSize < MAX_CODE_SIZE) codeSize++
        }

        emit(clear)
        if (data.isEmpty()) {
            emit(end)
            flushBits()
            return
        }
        var prefix = data[0].toInt() and 0xff
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xff
            val key = (prefix shl 8) or k
            var slot = (key * 31 + (key ushr 13)) and (TABLE_SIZE - 1)
            var found = -1
            while (keys[slot] != -1) {
                if (keys[slot] == key) { found = values[slot]; break }
                slot = (slot + 1) and (TABLE_SIZE - 1)
            }
            if (found >= 0) {
                prefix = found
                continue
            }
            emit(prefix)
            if (next < MAX_CODES) {
                keys[slot] = key
                values[slot] = next++
            } else {
                // table full: tell the decoder to start over
                emit(clear)
                keys.fill(-1)
                next = clear + 2
                codeSize = minCodeSize + 1
            }
            prefix = k
        }
        emit(prefix)
        emit(end)
        flushBits()
    }

    private fun flushBits() {
        if (bitCount > 0) addByte(bitBuffer and 0xff)
        bitBuffer = 0
        bitCount = 0
        if (blockSize > 0) writeBlock()
        out.write(0) // block terminator
    }

    private fun addByte(b: Int) {
        block[blockSize++] = b.toByte()
        if (blockSize == 255) writeBlock()
    }

    private fun writeBlock() {
        out.write(blockSize)
        out.write(block, 0, blockSize)
        blockSize = 0
    }

    companion object {
        private const val MAX_CODE_SIZE = 12
        private const val MAX_CODES = 1 shl MAX_CODE_SIZE
        private const val TABLE_SIZE = 1 shl 13
    }
}
