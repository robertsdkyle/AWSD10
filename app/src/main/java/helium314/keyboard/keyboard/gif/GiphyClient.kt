// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import helium314.keyboard.latin.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Tweak these to change how the GIF panel behaves. */
object GifConfig {
    /** GIPHY content rating: "g", "pg", "pg-13" or "r". "r" is the least filtered GIPHY allows. */
    const val RATING = "r"
    /** How many GIFs to load per page. */
    const val PAGE_SIZE = 30
    /** Stop loading more after this many results. */
    const val MAX_RESULTS = 300
    /** Delete the words you typed when you send a GIF that was searched from them. */
    const val REPLACE_TYPED_QUERY = true
    /** Wait this long after the last keystroke in the search field before searching. */
    const val SEARCH_DEBOUNCE_MS = 300L
    /** How many recently sent GIFs to remember. */
    const val RECENT_MAX = 24
    /** Grid tiles aim for this width; wider panels (tablets, landscape) get more columns. */
    const val TILE_TARGET_DP = 110
    const val MIN_COLUMNS = 3
    /** Quick-pick chips shown at the top of the panel. */
    val QUICK_PICKS = listOf(
        "lol", "yes", "no", "omg", "love you", "hype", "mad", "sad", "thank you",
        "wow", "nope", "dance", "facepalm", "good morning", "good night", "bruh"
    )
}

/** Thin GIPHY API client. Requests are cancellable: cancelling the coroutine closes the connection. */
object GiphyClient {
    data class Gif(
        val id: String,
        val title: String,
        /** Small rendition for the grid. */
        val previewUrl: String,
        /** Full rendition, only downloaded when the GIF is sent. */
        val sendUrl: String,
        val fallbackSendUrl: String,
        val pageUrl: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("title", title).put("preview", previewUrl)
            .put("send", sendUrl).put("fallback", fallbackSendUrl).put("page", pageUrl)

        companion object {
            fun fromJson(json: JSONObject): Gif? {
                val preview = json.optString("preview")
                val send = json.optString("send")
                if (preview.isEmpty() || send.isEmpty()) return null
                return Gif(
                    id = json.optString("id"),
                    title = json.optString("title").ifBlank { "GIF" },
                    previewUrl = preview,
                    sendUrl = send,
                    fallbackSendUrl = json.optString("fallback").ifEmpty { send },
                    pageUrl = json.optString("page").ifEmpty { send },
                )
            }
        }
    }

    data class Page(val gifs: List<Gif>, val totalCount: Int)

    class MissingKeyException : Exception("No GIPHY API key set")

    val hasApiKey get() = BuildConfig.GIPHY_API_KEY.isNotBlank()

    /** Search GIPHY, or get trending GIFs when [query] is null or blank. */
    suspend fun fetch(query: String?, offset: Int, lang: String): Page {
        if (!hasApiKey) throw MissingKeyException()
        val base = if (query.isNullOrBlank()) "https://api.giphy.com/v1/gifs/trending?"
            else "https://api.giphy.com/v1/gifs/search?q=${enc(query.trim())}&lang=${enc(lang)}&"
        val url = base + "api_key=${enc(BuildConfig.GIPHY_API_KEY)}" +
            "&limit=${GifConfig.PAGE_SIZE}&offset=$offset&rating=${GifConfig.RATING}&bundle=messaging_non_clips"
        val json = JSONObject(String(download(url, 2 * 1024 * 1024), Charsets.UTF_8))
        val data = json.optJSONArray("data") ?: return Page(emptyList(), 0)
        val result = ArrayList<Gif>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val images = item.optJSONObject("images") ?: continue
            fun img(name: String) = images.optJSONObject(name)?.optString("url").orEmpty()
            val preview = img("fixed_width_small").ifEmpty { img("fixed_height_small") }.ifEmpty { img("preview_gif") }
            val send = img("downsized").ifEmpty { img("original") }
            if (preview.isEmpty() || send.isEmpty()) continue
            result.add(Gif(
                id = item.optString("id"),
                title = item.optString("title").ifBlank { "GIF" },
                previewUrl = preview,
                sendUrl = send,
                fallbackSendUrl = img("fixed_height").ifEmpty { send },
                pageUrl = item.optString("url").ifBlank { send },
            ))
        }
        val total = json.optJSONObject("pagination")?.optInt("total_count", -1) ?: -1
        return Page(result, total)
    }

    /**
     * Downloads a URL into memory. Throws if it's bigger than [maxBytes].
     * Cancelling the calling coroutine disconnects, so a stale request never keeps running.
     */
    suspend fun download(url: String, maxBytes: Int = 15 * 1024 * 1024): ByteArray = coroutineScope {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "*/*")
        val work = async(Dispatchers.IO) {
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > maxBytes) throw IOException("File too large")
                    }
                }
                out.toByteArray()
            } finally {
                connection.disconnect()
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            // blocking reads ignore coroutine cancellation; closing the connection makes them fail fast
            connection.disconnect()
            throw e
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
