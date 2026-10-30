// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import helium314.keyboard.latin.BuildConfig
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
    /** Quick-pick chips shown at the top of the panel. */
    val QUICK_PICKS = listOf(
        "lol", "yes", "no", "omg", "love you", "hype", "mad", "sad", "thank you",
        "wow", "nope", "dance", "facepalm", "good morning", "good night", "bruh"
    )
}

/** Thin GIPHY API client. Every call here blocks, so run it off the main thread. */
object GiphyClient {
    data class Gif(
        val id: String,
        val title: String,
        val previewUrl: String,
        val sendUrl: String,
        val fallbackSendUrl: String,
        val pageUrl: String,
    )

    class MissingKeyException : Exception("No GIPHY API key set")

    val hasApiKey get() = BuildConfig.GIPHY_API_KEY.isNotBlank()

    /** Search GIPHY, or get trending GIFs when [query] is null or blank. */
    fun fetch(query: String?, offset: Int, lang: String): List<Gif> {
        if (!hasApiKey) throw MissingKeyException()
        val base = if (query.isNullOrBlank()) "https://api.giphy.com/v1/gifs/trending?"
            else "https://api.giphy.com/v1/gifs/search?q=${enc(query.trim())}&lang=${enc(lang)}&"
        val url = base + "api_key=${enc(BuildConfig.GIPHY_API_KEY)}" +
            "&limit=${GifConfig.PAGE_SIZE}&offset=$offset&rating=${GifConfig.RATING}&bundle=messaging_non_clips"
        val json = JSONObject(String(download(url), Charsets.UTF_8))
        val data = json.optJSONArray("data") ?: return emptyList()
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
        return result
    }

    /** Download a URL into memory. Throws if it's bigger than [maxBytes]. */
    fun download(url: String, maxBytes: Int = 15 * 1024 * 1024): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "*/*")
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
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
