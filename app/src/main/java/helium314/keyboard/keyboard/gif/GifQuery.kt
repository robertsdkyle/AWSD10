// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

/** What the user had typed when they opened the GIF panel. */
class GifQuery(@JvmField val text: String, @JvmField val deleteLength: Int) {
    companion object {
        @JvmField val EMPTY = GifQuery("", 0)
        private const val MAX_WORDS = 6

        /** Use the last few words on the current line as the search. */
        @JvmStatic
        fun fromTextBeforeCursor(before: CharSequence?): GifQuery {
            if (before.isNullOrEmpty()) return EMPTY
            val line = before.substring(before.lastIndexOf('\n') + 1)
            if (line.isBlank()) return EMPTY
            var start = line.length
            var words = 0
            var i = line.length
            while (i > 0 && line[i - 1].isWhitespace()) i--
            while (i > 0 && words < MAX_WORDS) {
                while (i > 0 && !line[i - 1].isWhitespace()) i--
                start = i
                words++
                while (i > 0 && line[i - 1].isWhitespace()) i--
            }
            val text = line.substring(start).trim()
            return if (text.isEmpty()) EMPTY else GifQuery(text, line.length - start)
        }
    }
}

fun interface GifTextDeleter { fun delete(length: Int) }
