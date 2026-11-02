// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.gif.sticker.StickerRecipe
import helium314.keyboard.keyboard.gif.sticker.StickerRenderer
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puts a GIF or sticker into the app being typed in. Apps that accept keyboard images get the
 * file through commitContent; apps that don't get a link (GIFs) or the plain text (stickers).
 */
class GifSender(context: Context) {
    private val context = context.applicationContext

    /** Sends a GIPHY GIF. Returns true if it went in as an image, false if the link was typed instead. */
    suspend fun sendGif(gif: GiphyClient.Gif, editorInfo: EditorInfo?, listener: KeyboardActionListener,
                        beforeInsert: () -> Unit): Boolean {
        if (accepts(editorInfo, MIME_GIF)) {
            val uri = orNull("downloading GIF") {
                val bytes = orNull("downloading full GIF") { GiphyClient.download(gif.sendUrl, 8 * 1024 * 1024) }
                    ?: GiphyClient.download(gif.fallbackSendUrl)
                withContext(Dispatchers.IO) { save("gif_${safeName(gif.id)}.gif", bytes) }
            }
            if (uri != null) {
                beforeInsert()
                listener.onContent(InputContentInfoCompat(uri, ClipDescription(gif.title, arrayOf(MIME_GIF)), Uri.parse(gif.pageUrl)))
                return true
            }
        }
        beforeInsert()
        listener.onTextInput(gif.sendUrl)
        return false
    }

    /** Sends a text sticker in the best format the app takes: GIF, then animated WebP, then a still PNG, then text. */
    suspend fun sendSticker(recipe: StickerRecipe, editorInfo: EditorInfo?, listener: KeyboardActionListener,
                            beforeInsert: () -> Unit): Boolean {
        val format = when {
            accepts(editorInfo, MIME_GIF) -> StickerRenderer.Format.GIF
            accepts(editorInfo, StickerRenderer.Format.WEBP.mimeType) -> StickerRenderer.Format.WEBP
            accepts(editorInfo, StickerRenderer.Format.PNG.mimeType) -> StickerRenderer.Format.PNG
            else -> null
        }
        if (format != null) {
            val uri = orNull("rendering sticker") {
                withContext(Dispatchers.Default) {
                    val bytes = StickerRenderer.render(recipe, format) { !isActive }
                    save("sticker_${recipe.toUriString().hashCode().toUInt()}_${System.currentTimeMillis()}.${format.extension}", bytes)
                }
            }
            if (uri != null) {
                beforeInsert()
                val label = context.getString(R.string.gif_sticker_label, recipe.plainText)
                listener.onContent(InputContentInfoCompat(uri, ClipDescription(label, arrayOf(format.mimeType)), null))
                return true
            }
        }
        beforeInsert()
        listener.onTextInput(recipe.plainText)
        return false
    }

    /** Like runCatching, but lets cancellation through: a closed panel must not fall back to typing a link. */
    private suspend fun <T> orNull(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed", e)
        null
    }

    private fun save(name: String, bytes: ByteArray): Uri {
        val dir = dir(context)
        trim(dir)
        val file = File(dir, name)
        file.writeBytes(bytes)
        if (BuildConfig.DEBUG) Log.d(TAG, "saved ${file.name} (${bytes.size} bytes) for sending")
        return FileProvider.getUriForFile(context, context.getString(R.string.clipboard_provider_authority), file)
    }

    companion object {
        private const val TAG = "GifSender"
        const val MIME_GIF = "image/gif"
        const val GIF_DIR = "gifs"
        /** Receiving apps usually copy the file right away; keep a few in case one reads it late. */
        private const val KEEP_FILES = 20
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        fun accepts(editorInfo: EditorInfo?, mimeType: String): Boolean {
            if (editorInfo == null) return false
            return EditorInfoCompat.getContentMimeTypes(editorInfo).any { ClipDescription.compareMimeTypes(mimeType, it) }
        }

        fun dir(context: Context) = File(context.filesDir, GIF_DIR).apply { mkdirs() }

        /** Deletes old files handed to other apps: anything past the newest [KEEP_FILES], or older than a day. */
        fun trim(dir: File) {
            val now = System.currentTimeMillis()
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEachIndexed { i, file ->
                if (i >= KEEP_FILES || now - file.lastModified() > MAX_AGE_MS) file.delete()
            }
        }

        private fun safeName(id: String) = id.filter { it.isLetterOrDigit() }.ifEmpty { "gif" }
    }
}
