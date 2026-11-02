// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import android.content.Context
import org.json.JSONArray

/** The GIFs you sent most recently, newest first. Stored only on the device. */
class GifRecents(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<GiphyClient.Gif> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(GiphyClient.Gif::fromJson) }
        }.getOrDefault(emptyList())
    }

    fun add(gif: GiphyClient.Gif) {
        val list = listOf(gif) + load().filter { it.id != gif.id }
        val array = JSONArray()
        list.take(GifConfig.RECENT_MAX).forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS_NAME = "gif_panel"
        private const val KEY = "recent_gifs"
    }
}
