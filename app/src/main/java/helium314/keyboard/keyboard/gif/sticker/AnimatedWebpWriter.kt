// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif.sticker

import java.io.ByteArrayOutputStream

/**
 * Packs single-frame WebP files (as made by Android's Bitmap.compress) into one animated WebP.
 *
 * Android can only encode still WebP images. An animated WebP is a RIFF container with a VP8X
 * header, an ANIM chunk and one ANMF chunk per frame, where each ANMF holds the image chunks
 * (optional ALPH plus VP8, or VP8L) of a still WebP. So this just re-wraps those chunks.
 */
class AnimatedWebpWriter(private val width: Int, private val height: Int) {
    private val frames = ByteArrayOutputStream()
    private var frameCount = 0
    private var anyAlpha = false

    /** Adds one frame. [webp] must be a complete still WebP file of the full canvas size. */
    fun addFrame(webp: ByteArray, durationMs: Int) {
        val chunks = readChunks(webp)
        val image = chunks.filter { it.fourCc == "ALPH" || it.fourCc == "VP8 " || it.fourCc == "VP8L" }
        require(image.any { it.fourCc == "VP8 " || it.fourCc == "VP8L" }) { "not a still WebP image" }
        if (image.any { it.fourCc == "ALPH" || it.fourCc == "VP8L" }) anyAlpha = true

        val payload = ByteArrayOutputStream()
        write24(payload, 0) // frame x / 2
        write24(payload, 0) // frame y / 2
        write24(payload, width - 1)
        write24(payload, height - 1)
        write24(payload, durationMs.coerceIn(1, 0xffffff))
        payload.write(0x03) // don't blend with the previous frame, clear to background afterwards
        image.forEach { writeChunk(payload, it.fourCc, it.data) }
        writeChunk(frames, "ANMF", payload.toByteArray())
        frameCount++
    }

    fun toByteArray(): ByteArray {
        check(frameCount > 0) { "no frames" }
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))

        val vp8x = ByteArrayOutputStream()
        vp8x.write((if (anyAlpha) 0x10 else 0) or 0x02) // alpha, animation
        vp8x.write(0); vp8x.write(0); vp8x.write(0)
        write24(vp8x, width - 1)
        write24(vp8x, height - 1)
        writeChunk(body, "VP8X", vp8x.toByteArray())

        val anim = ByteArrayOutputStream()
        anim.write(0); anim.write(0); anim.write(0); anim.write(0) // transparent background
        anim.write(0); anim.write(0) // loop forever
        writeChunk(body, "ANIM", anim.toByteArray())

        frames.writeTo(body)

        val out = ByteArrayOutputStream(body.size() + 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        write32(out, body.size())
        body.writeTo(out)
        return out.toByteArray()
    }

    internal class Chunk(val fourCc: String, val data: ByteArray)

    companion object {
        /** Lists the chunks inside a RIFF/WEBP file. */
        internal fun readChunks(webp: ByteArray): List<Chunk> {
            require(webp.size >= 12 && String(webp, 0, 4, Charsets.US_ASCII) == "RIFF"
                    && String(webp, 8, 4, Charsets.US_ASCII) == "WEBP") { "not a WebP file" }
            val chunks = ArrayList<Chunk>()
            var pos = 12
            while (pos + 8 <= webp.size) {
                val fourCc = String(webp, pos, 4, Charsets.US_ASCII)
                val size = (webp[pos + 4].toInt() and 0xff) or ((webp[pos + 5].toInt() and 0xff) shl 8) or
                        ((webp[pos + 6].toInt() and 0xff) shl 16) or ((webp[pos + 7].toInt() and 0xff) shl 24)
                require(size >= 0 && pos + 8 + size <= webp.size) { "broken WebP chunk $fourCc" }
                chunks.add(Chunk(fourCc, webp.copyOfRange(pos + 8, pos + 8 + size)))
                pos += 8 + size + (size and 1) // chunks are padded to an even size
            }
            return chunks
        }

        private fun writeChunk(out: ByteArrayOutputStream, fourCc: String, data: ByteArray) {
            out.write(fourCc.toByteArray(Charsets.US_ASCII))
            write32(out, data.size)
            out.write(data)
            if (data.size and 1 == 1) out.write(0)
        }

        private fun write24(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff)
        }

        private fun write32(out: ByteArrayOutputStream, value: Int) {
            write24(out, value)
            out.write((value shr 24) and 0xff)
        }
    }
}
