// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gif

import helium314.keyboard.keyboard.gif.GifQuery
import helium314.keyboard.keyboard.gif.sticker.AnimatedWebpWriter
import helium314.keyboard.keyboard.gif.sticker.GifEncoder
import helium314.keyboard.keyboard.gif.sticker.GifPalette
import helium314.keyboard.keyboard.gif.sticker.StickerAnim
import helium314.keyboard.keyboard.gif.sticker.StickerRecipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plain JVM tests for the GIF panel's pure parts: recipes, the GIF encoder and the WebP packer. */
class StickerTest {
    @Test fun recipeRoundTrip() {
        val recipe = StickerRecipe(StickerAnim.BOUNCE, listOf("LOL", "ok & yes=1"), 256, 200, 10, 90)
        assertEquals(recipe, StickerRecipe.parse(recipe.toUriString()))
    }

    @Test fun recipeParsesTheDocumentedExample() {
        assertEquals(
            StickerRecipe(StickerAnim.BOUNCE, listOf("LOL", "ok"), 256, 256),
            StickerRecipe.parse("sticker://text?anim=bounce&line=LOL&line=ok&w=256&h=256")
        )
    }

    @Test fun recipeRejectsBadInput() {
        assertNull(StickerRecipe.parse("sticker://text?anim=nope&line=x"))
        assertNull(StickerRecipe.parse("sticker://text?anim=pop"))
        assertNull(StickerRecipe.parse("https://example.com/?anim=pop&line=x"))
        assertNull(StickerRecipe.parse("sticker://text?anim=pop&line=x&w=abc"))
    }

    @Test fun recipeClampsSizesAndLines() {
        val r = StickerRecipe.parse("sticker://text?anim=pop&line=a&line=b&line=c&line=d&w=99999&h=1&start=80&stop=20")!!
        assertEquals(3, r.lines.size)
        assertEquals(StickerRecipe.MAX_SIZE, r.width)
        assertEquals(StickerRecipe.MIN_SIZE, r.height)
        assertEquals(80, r.startPercent)
        assertEquals(80, r.stopPercent)
    }

    @Test fun wrapKeepsShortTextOnOneLine() {
        assertEquals(listOf("lol"), StickerRecipe.wrap("  lol "))
        assertEquals(emptyList(), StickerRecipe.wrap("   "))
        assertTrue(StickerRecipe.wrap("happy birthday to you my friend").size in 2..3)
        assertTrue(StickerRecipe.wrap("a b c d e f g h i j k l m n o p q r s t u v w x y z").size <= StickerRecipe.MAX_LINES)
    }

    @Test fun gifEncoderOutputDecodesExactly() {
        val w = 120
        val h = 80
        // three frames with transparent stripes and plenty of colors, to exercise LZW table resets
        val frames = (0 until 3).map { f ->
            IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                if ((x + f * 20) % 50 < 10) 0
                else (0xff shl 24) or (((x * 7 + f * 30) and 0xff) shl 16) or (((y * 3) and 0xff) shl 8) or ((x xor y) and 0xff)
            }
        }
        val paletteBuilder = GifPalette.Builder()
        frames.forEach { paletteBuilder.add(it) }
        val palette = paletteBuilder.build()
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, w, h, palette)
        frames.forEach { encoder.addFrame(it, 60) }
        encoder.finish()

        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(out.toByteArray()))
        assertEquals(3, reader.getNumImages(true))
        for (f in frames.indices) {
            val image = reader.read(f)
            for (y in 0 until h) for (x in 0 until w) {
                val index = palette.indexOf(frames[f][y * w + x])
                val got = image.getRGB(x, y)
                if (index == GifPalette.TRANSPARENT_INDEX) assertEquals(0, got ushr 24, "frame $f ($x,$y) should be transparent")
                else assertEquals(palette.colors[index], got and 0xffffff, "frame $f ($x,$y)")
            }
        }
    }

    @Test fun webpWriterBuildsAnAnimatedContainer() {
        val frame = stillWebp("VP8L", byteArrayOf(0x2f, 1, 2, 3, 4)) // odd payload checks padding
        val writer = AnimatedWebpWriter(64, 48)
        writer.addFrame(frame, 100)
        writer.addFrame(frame, 100)
        val bytes = writer.toByteArray()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(bytes.size - 8, le32(bytes, 4))
        assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
        val chunks = chunkNames(bytes)
        assertEquals(listOf("VP8X", "ANIM", "ANMF", "ANMF"), chunks)
        assertEquals(0x12, bytes[20].toInt()) // VP8X flags: alpha + animation
        assertEquals(63, le24(bytes, 24))
        assertEquals(47, le24(bytes, 27))
    }

    @Test fun gifQueryTakesTheLastWordsOfTheLine() {
        val q = GifQuery.fromTextBeforeCursor("first line\nhappy birthday ")
        assertEquals("happy birthday", q.text)
        assertEquals("happy birthday ".length, q.deleteLength)
        assertEquals("", GifQuery.fromTextBeforeCursor("hello\n").text)
        assertEquals("c d e f g h", GifQuery.fromTextBeforeCursor("a b c d e f g h").text)
    }

    private fun stillWebp(fourCc: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val padded = payload.size + (payload.size and 1)
        writeLe32(out, 4 + 8 + padded)
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(fourCc.toByteArray(Charsets.US_ASCII))
        writeLe32(out, payload.size)
        out.write(payload)
        if (payload.size and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun chunkNames(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        var pos = 12
        while (pos + 8 <= bytes.size) {
            names.add(String(bytes, pos, 4, Charsets.US_ASCII))
            val size = le32(bytes, pos + 4)
            pos += 8 + size + (size and 1)
        }
        assertEquals(bytes.size, pos)
        return names
    }

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
    }

    private fun le24(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16)
    private fun le32(b: ByteArray, at: Int) = le24(b, at) or ((b[at + 3].toInt() and 0xff) shl 24)
}
