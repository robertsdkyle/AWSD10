// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif.sticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.Log
import helium314.keyboard.latin.BuildConfig
import java.io.ByteArrayOutputStream
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws text stickers from a [StickerRecipe] and encodes them. Everything here is CPU work:
 * call it from a background thread (Glide's source executor or Dispatchers.Default).
 */
object StickerRenderer {
    private const val TAG = "StickerRenderer"
    const val FRAME_COUNT = 20
    const val FRAME_DELAY_MS = 60

    class CancelledException : Exception("sticker render cancelled")

    enum class Format(val mimeType: String, val extension: String) {
        GIF("image/gif", "gif"),
        WEBP("image/webp", "webp"),
        PNG("image/png", "png"),
    }

    fun render(recipe: StickerRecipe, format: Format, isCancelled: () -> Boolean = { false }): ByteArray {
        val started = SystemClock.elapsedRealtime()
        try {
            val bytes = when (format) {
                Format.GIF -> renderGif(recipe, isCancelled)
                Format.WEBP -> renderWebp(recipe, isCancelled)
                Format.PNG -> renderPng(recipe)
            }
            if (BuildConfig.DEBUG)
                Log.d(TAG, "rendered $format ${recipe.toUriString()} in ${SystemClock.elapsedRealtime() - started} ms, ${bytes.size} bytes")
            return bytes
        } catch (e: CancelledException) {
            if (BuildConfig.DEBUG) Log.d(TAG, "cancelled ${recipe.toUriString()}")
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "rendering $format ${recipe.toUriString()} failed", e)
            throw e
        }
    }

    /** Animation progress (0 until 1) for each frame, honoring the recipe's start / stop percent. */
    private fun frameTimes(recipe: StickerRecipe): FloatArray {
        val start = recipe.startPercent / 100f
        val stop = recipe.stopPercent / 100f
        if (stop - start < 0.001f) return floatArrayOf(start)
        val count = max(2, (FRAME_COUNT * (stop - start) + 0.5f).toInt())
        // the loop restarts after the last frame, so don't include the stop point itself
        return FloatArray(count) { start + (stop - start) * it / count }
    }

    private fun renderGif(recipe: StickerRecipe, isCancelled: () -> Boolean): ByteArray {
        val times = frameTimes(recipe)
        val painter = StickerPainter(recipe)
        val bitmap = Bitmap.createBitmap(recipe.width, recipe.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(recipe.width * recipe.height)
        try {
            // first pass picks the palette, second pass encodes; rendering twice beats holding every frame
            val paletteBuilder = GifPalette.Builder()
            for (t in times) {
                if (isCancelled()) throw CancelledException()
                painter.draw(canvas, t)
                bitmap.getPixels(pixels, 0, recipe.width, 0, 0, recipe.width, recipe.height)
                paletteBuilder.add(pixels)
            }
            val out = ByteArrayOutputStream(32 * 1024)
            val encoder = GifEncoder(out, recipe.width, recipe.height, paletteBuilder.build())
            for (t in times) {
                if (isCancelled()) throw CancelledException()
                painter.draw(canvas, t)
                bitmap.getPixels(pixels, 0, recipe.width, 0, 0, recipe.width, recipe.height)
                encoder.addFrame(pixels, FRAME_DELAY_MS)
            }
            encoder.finish()
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderWebp(recipe: StickerRecipe, isCancelled: () -> Boolean): ByteArray {
        val times = frameTimes(recipe)
        val painter = StickerPainter(recipe)
        val bitmap = Bitmap.createBitmap(recipe.width, recipe.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        try {
            val writer = AnimatedWebpWriter(recipe.width, recipe.height)
            val frame = ByteArrayOutputStream(16 * 1024)
            for (t in times) {
                if (isCancelled()) throw CancelledException()
                painter.draw(canvas, t)
                frame.reset()
                compressWebp(bitmap, frame)
                writer.addFrame(frame.toByteArray(), FRAME_DELAY_MS)
            }
            return writer.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderPng(recipe: StickerRecipe): ByteArray {
        val still = if (recipe.startPercent == recipe.stopPercent) recipe else recipe.stillFrame()
        val bitmap = Bitmap.createBitmap(still.width, still.height, Bitmap.Config.ARGB_8888)
        try {
            StickerPainter(still).draw(Canvas(bitmap), still.startPercent / 100f)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun compressWebp(bitmap: Bitmap, out: ByteArrayOutputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
        }
    }
}

/** Lays out the recipe's text once and draws any frame of its animation. */
private class StickerPainter(private val recipe: StickerRecipe) {
    private class Cluster(val text: String, val x: Float, val y: Float, val index: Int)

    private val w = recipe.width.toFloat()
    private val h = recipe.height.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = TYPEFACE
        textAlign = Paint.Align.LEFT
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val clusters = ArrayList<Cluster>()
    private val clusterCount: Int
    private val outlineWidth: Float
    private val extrude: Float
    private val hsv = floatArrayOf(0f, 0.8f, 1f)

    init {
        // measure at a reference size, then scale so the widest line and the whole block fit
        paint.textSize = 100f
        val widest = recipe.lines.maxOf { paint.measureText(it) }.coerceAtLeast(1f)
        val lineHeight100 = paint.fontSpacing * 0.92f
        val fitWidth = 100f * w * 0.80f / widest
        val fitHeight = 100f * h * 0.70f / (lineHeight100 * recipe.lines.size)
        val size = min(min(fitWidth, fitHeight), h * 0.42f)
        paint.textSize = size
        outlineWidth = size * 0.15f
        extrude = size * 0.055f

        val lineHeight = paint.fontSpacing * 0.92f
        val metrics = paint.fontMetrics
        val blockHeight = lineHeight * (recipe.lines.size - 1) + (metrics.descent - metrics.ascent)
        var baseline = (h - blockHeight) / 2f - metrics.ascent
        var index = 0
        val breaker = BreakIterator.getCharacterInstance()
        for (line in recipe.lines) {
            val lineStart = (w - paint.measureText(line)) / 2f
            breaker.setText(line)
            var start = breaker.first()
            var end = breaker.next()
            while (end != BreakIterator.DONE) {
                val text = line.substring(start, end)
                if (text.isNotBlank())
                    clusters.add(Cluster(text, lineStart + paint.measureText(line, 0, start), baseline, index))
                index++
                start = end
                end = breaker.next()
            }
            baseline += lineHeight
        }
        clusterCount = max(1, index)
    }

    fun draw(canvas: Canvas, t: Float) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.save()
        when (recipe.anim) {
            StickerAnim.WOBBLE -> {
                val angle = 9f * sin(2 * PI * t).toFloat()
                val scale = 1f + 0.04f * sin(4 * PI * t).toFloat()
                canvas.rotate(angle, w / 2, h / 2)
                canvas.scale(scale, scale, w / 2, h / 2)
            }
            StickerAnim.POP -> {
                val scale = popScale(t)
                canvas.scale(scale, scale, w / 2, h / 2)
            }
            else -> {}
        }
        // three passes so no letter's outline is drawn over a neighbour's fill
        for (pass in 0..2) {
            for (c in clusters) {
                val dy = offsetY(c, t)
                when (pass) {
                    0 -> {
                        paint.style = Paint.Style.FILL_AND_STROKE
                        paint.strokeWidth = outlineWidth
                        paint.color = EXTRUDE_COLOR
                        canvas.drawText(c.text, c.x + extrude, c.y + dy + extrude, paint)
                    }
                    1 -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = outlineWidth
                        paint.color = OUTLINE_COLOR
                        canvas.drawText(c.text, c.x, c.y + dy, paint)
                    }
                    else -> {
                        paint.style = Paint.Style.FILL
                        paint.color = fillColor(c, t)
                        canvas.drawText(c.text, c.x, c.y + dy, paint)
                    }
                }
            }
        }
        canvas.restore()
    }

    private fun offsetY(c: Cluster, t: Float): Float = when (recipe.anim) {
        // each letter hops in turn, a wave running through the word
        StickerAnim.BOUNCE -> -h * 0.09f * max(0f, sin(2 * PI * (t - c.index * 0.06f)).toFloat())
        StickerAnim.RAINBOW -> h * 0.025f * sin(2 * PI * (t - c.index * 0.08f)).toFloat()
        else -> 0f
    }

    private fun fillColor(c: Cluster, t: Float): Int = when (recipe.anim) {
        StickerAnim.BOUNCE -> 0xffffd23f.toInt()
        StickerAnim.WOBBLE -> 0xff4fd1ff.toInt()
        StickerAnim.POP -> 0xffff6fb5.toInt()
        StickerAnim.RAINBOW -> {
            hsv[0] = ((t * 360f + c.index * 360f / max(6, clusterCount)) % 360f + 360f) % 360f
            Color.HSVToColor(hsv)
        }
    }

    /** Grows in fast with a small overshoot, then gently breathes until the loop restarts. */
    private fun popScale(t: Float): Float {
        val grow = 0.3f
        if (t < grow) {
            val p = t / grow
            val back = 1.70158f
            val q = p - 1f
            val eased = 1f + (back + 1f) * q * q * q + back * q * q // ease out with overshoot
            return 0.15f + 0.85f * eased
        }
        return 1f + 0.03f * abs(sin(2 * PI * (t - grow) / (1f - grow)).toFloat())
    }

    companion object {
        private val OUTLINE_COLOR = 0xff1d1d24.toInt()
        private val EXTRUDE_COLOR = 0xff000000.toInt()
        private val TYPEFACE: Typeface by lazy {
            // Roboto Black where the system has it, plain bold otherwise; no bundled fonts
            val black = Typeface.create("sans-serif-black", Typeface.NORMAL)
            if (black != null && black != Typeface.DEFAULT) black else Typeface.DEFAULT_BOLD
        }
    }
}
