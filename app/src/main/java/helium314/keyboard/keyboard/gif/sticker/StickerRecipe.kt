// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif.sticker

import java.net.URLDecoder
import java.net.URLEncoder

/** The animation styles a text sticker can use. [id] is what appears in the recipe URI. */
enum class StickerAnim(val id: String) {
    BOUNCE("bounce"),
    WOBBLE("wobble"),
    RAINBOW("rainbow"),
    POP("pop");

    companion object {
        fun fromId(id: String?): StickerAnim? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Everything needed to draw one text sticker, written as a URI so it can be used as an image
 * address and as a cache key, e.g. `sticker://text?anim=bounce&line=LOL&line=ok&w=256&h=256`.
 *
 * [startPercent] and [stopPercent] pick which part of the animation loop gets rendered
 * (0 to 100 is the whole loop; the same value twice gives a single still frame).
 *
 * Plain Kotlin on purpose (no android.net.Uri) so it can be unit tested on the JVM.
 */
data class StickerRecipe(
    val anim: StickerAnim,
    val lines: List<String>,
    val width: Int = DEFAULT_SIZE,
    val height: Int = DEFAULT_SIZE,
    val startPercent: Int = 0,
    val stopPercent: Int = 100,
) {
    /** The canonical URI. Two equal recipes always give the same string, so it's safe as a cache key. */
    fun toUriString(): String = buildString {
        append(SCHEME).append("://").append(HOST).append("?anim=").append(anim.id)
        lines.forEach { append("&line=").append(enc(it)) }
        append("&w=").append(width).append("&h=").append(height)
        if (startPercent != 0 || stopPercent != 100) append("&start=").append(startPercent).append("&stop=").append(stopPercent)
    }

    /** All lines on one line, for when an app can't take images and gets plain text instead. */
    val plainText: String get() = lines.joinToString(" ")

    /** A copy that renders as a single still frame (for apps that only take PNG). */
    fun stillFrame(percent: Int = 60) = copy(startPercent = percent, stopPercent = percent)

    override fun toString() = toUriString()

    companion object {
        const val SCHEME = "sticker"
        const val HOST = "text"
        const val DEFAULT_SIZE = 256
        const val MIN_SIZE = 64
        const val MAX_SIZE = 512
        const val MAX_LINES = 3
        const val MAX_LINE_LENGTH = 24
        /** Lines longer than this get wrapped when building a recipe from free text. */
        private const val WRAP_AT = 12

        /** Parses a recipe URI. Returns null for anything that isn't a valid text sticker. */
        fun parse(uri: String?): StickerRecipe? {
            if (uri == null) return null
            val prefix = "$SCHEME://$HOST?"
            if (!uri.startsWith(prefix)) return null
            var anim: StickerAnim? = null
            val lines = ArrayList<String>()
            var w = DEFAULT_SIZE
            var h = DEFAULT_SIZE
            var start = 0
            var stop = 100
            for (part in uri.substring(prefix.length).split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq)
                val value = runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull() ?: return null
                when (key) {
                    "anim" -> anim = StickerAnim.fromId(value) ?: return null
                    "line" -> if (lines.size < MAX_LINES) value.trim().take(MAX_LINE_LENGTH).takeIf { it.isNotEmpty() }?.let { lines.add(it) }
                    "w" -> w = value.toIntOrNull() ?: return null
                    "h" -> h = value.toIntOrNull() ?: return null
                    "start" -> start = value.toIntOrNull() ?: return null
                    "stop" -> stop = value.toIntOrNull() ?: return null
                }
            }
            if (anim == null || lines.isEmpty()) return null
            start = start.coerceIn(0, 100)
            stop = stop.coerceIn(start, 100)
            return StickerRecipe(anim, lines, w.coerceIn(MIN_SIZE, MAX_SIZE), h.coerceIn(MIN_SIZE, MAX_SIZE), start, stop)
        }

        /** Builds a recipe from what the user typed, wrapping long text into up to [MAX_LINES] lines. */
        fun fromText(text: String, anim: StickerAnim, size: Int = DEFAULT_SIZE): StickerRecipe? {
            val lines = wrap(text)
            if (lines.isEmpty()) return null
            val s = size.coerceIn(MIN_SIZE, MAX_SIZE)
            return StickerRecipe(anim, lines, s, s)
        }

        /** Greedy word wrap into at most [MAX_LINES] lines of roughly [WRAP_AT] characters. */
        fun wrap(text: String): List<String> {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            val total = words.sumOf { it.length } + words.size - 1
            // aim for evenly filled lines rather than a long first line and a short last one
            val lineCount = ((total + WRAP_AT - 1) / WRAP_AT).coerceIn(1, MAX_LINES)
            val target = (total + lineCount - 1) / lineCount
            val lines = ArrayList<String>()
            val current = StringBuilder()
            for (word in words) {
                if (current.isNotEmpty() && current.length + 1 + word.length > target && lines.size < MAX_LINES - 1) {
                    lines.add(current.toString())
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
            if (current.isNotEmpty()) lines.add(current.toString())
            return lines.map { if (it.length > MAX_LINE_LENGTH) it.take(MAX_LINE_LENGTH - 1) + "…" else it }
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
