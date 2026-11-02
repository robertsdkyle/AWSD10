// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import android.content.Context
import android.util.Log
import android.view.inputmethod.EditorInfo
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.gif.sticker.StickerAnim
import helium314.keyboard.keyboard.gif.sticker.StickerRecipe
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Implemented by KeyboardSwitcher, which owns the views the GIF mode swaps in and out. */
interface GifModeHost {
    /** Grid in place of the letters. */
    fun showGifBrowse()
    /** Letters back, with the GIF search strip on top, so keystrokes can fill the search field. */
    fun showGifTyping()
    /** Back to normal typing. */
    fun closeGifMode()
}

/**
 * The GIF keyboard mode: its own search text, its own feed, and a clear open / close lifecycle.
 * Views ([GifPanelView], [GifSearchStripView]) only render this state and forward taps here.
 * Everything runs on the main thread; network and rendering hop to background dispatchers.
 */
class GifController(context: Context) {
    enum class Mode { CLOSED, BROWSE, TYPING }
    enum class Status { LOADING, LOADED, EMPTY, ERROR, MISSING_KEY, RESTRICTED }

    sealed class Item {
        abstract val key: String
        data class Header(val title: Int) : Item() { override val key get() = "header:$title" }
        data class GifItem(val gif: GiphyClient.Gif, val section: String) : Item() { override val key get() = "$section:${gif.id}" }
        data class Stickers(val recipes: List<StickerRecipe>) : Item() { override val key get() = "stickers" }
        /** A full-width note under existing results, e.g. a failed page with a retry button. */
        data class Message(val text: Int, val retry: Boolean) : Item() { override val key get() = "message" }
    }

    fun interface Listener { fun onGifStateChanged() }

    private val appContext = context.applicationContext
    private val recentStore = GifRecents(appContext)
    private val sender = GifSender(appContext)
    private val listeners = LinkedHashSet<Listener>()
    private var scope: CoroutineScope? = null

    private var host: GifModeHost? = null
    private var editorInfo: EditorInfo? = null
    private var actionListener: KeyboardActionListener? = null
    private var deleter: GifTextDeleter? = null
    private var language = "en"

    var mode = Mode.CLOSED
        private set
    var typedQuery = GifQuery.EMPTY
        private set
    /** What's in the search field right now. */
    var query = ""
        private set
    /** The query the shown results belong to (lags [query] by the debounce while typing). */
    var activeQuery = ""
        private set
    var restricted = false
        private set
    var status = Status.LOADING
        private set
    var sending = false
        private set
    var recents: List<GiphyClient.Gif> = emptyList()
        private set
    private val results = ArrayList<GiphyClient.Gif>()
    private var offset = 0
    private var endReached = false
    private var pageFailed = false
    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    val isOpen get() = mode != Mode.CLOSED
    val isTyping get() = mode == Mode.TYPING

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }
    private fun changed() { listeners.toList().forEach { it.onGifStateChanged() } }

    /** Text stickers for the current search, one per animation style. */
    val stickers: List<StickerRecipe>
        get() = if (restricted || activeQuery.isBlank()) emptyList()
            else StickerAnim.entries.mapNotNull { StickerRecipe.fromText(activeQuery, it) }

    /** The grid's content, in sections. Sections with nothing in them get no header. */
    fun items(): List<Item> {
        if (restricted) return emptyList()
        val items = ArrayList<Item>()
        val stickerList = stickers
        if (stickerList.isNotEmpty()) {
            items.add(Item.Header(R.string.gif_section_stickers))
            items.add(Item.Stickers(stickerList))
        }
        if (activeQuery.isBlank() && recents.isNotEmpty()) {
            items.add(Item.Header(R.string.gif_section_recent))
            recents.forEach { items.add(Item.GifItem(it, "recent")) }
        }
        if (results.isNotEmpty()) {
            items.add(Item.Header(if (activeQuery.isBlank()) R.string.gif_trending
                else R.string.gif_section_gifs))
            results.forEach { items.add(Item.GifItem(it, "result")) }
        }
        // problems below existing content get a row; with no content at all the views show a centered status
        if (items.isNotEmpty()) when {
            pageFailed || status == Status.ERROR -> items.add(Item.Message(R.string.gif_load_failed, true))
            status == Status.EMPTY -> items.add(Item.Message(R.string.gif_no_results, false))
            status == Status.MISSING_KEY -> items.add(Item.Message(R.string.gif_missing_key, false))
        }
        return items
    }

    /** Flat list for the one-row strip shown while typing: stickers first, then GIFs. */
    fun stripItems(): List<Any> = if (restricted) emptyList() else stickers + results

    // ---------------------------------------------------------------- lifecycle

    fun open(query: GifQuery, restricted: Boolean, editorInfo: EditorInfo?, listener: KeyboardActionListener,
             deleter: GifTextDeleter, language: String, host: GifModeHost) {
        // a fresh session: nothing from the previous one may land in it
        cancelAll()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        this.host = host
        this.editorInfo = editorInfo
        this.actionListener = listener
        this.deleter = deleter
        this.language = language
        this.restricted = restricted
        this.typedQuery = if (restricted) GifQuery.EMPTY else query
        this.recents = if (restricted) emptyList() else recentStore.load()
        sending = false
        mode = Mode.BROWSE
        GifSender.trim(GifSender.dir(appContext))
        if (restricted) {
            this.query = ""
            activeQuery = ""
            results.clear()
            status = Status.RESTRICTED
            changed()
            return
        }
        this.query = typedQuery.text
        search(typedQuery.text)
    }

    fun close() {
        if (mode == Mode.CLOSED) return
        mode = Mode.CLOSED
        cancelAll()
        results.clear()
        host = null
        editorInfo = null
        actionListener = null
        deleter = null
        changed()
    }

    private fun cancelAll() {
        scope?.cancel()
        scope = null
        searchJob = null
        debounceJob = null
        sending = false
    }

    /** Asks the host to leave GIF mode; the host then calls [close]. */
    fun requestClose() {
        host?.closeGifMode() ?: close()
    }

    // ---------------------------------------------------------------- searching

    /** A chip or the search button: search right away. */
    fun selectQuery(text: String) {
        if (restricted) return
        query = text
        search(text)
    }

    fun retry() {
        if (restricted) return
        if (results.isEmpty()) search(activeQuery) else {
            pageFailed = false
            endReached = false
            loadMore()
        }
    }

    private fun search(text: String) {
        val scope = scope ?: return
        debounceJob?.cancel()
        debounceJob = null
        searchJob?.cancel() // the previous query's request is dropped, connection and all
        activeQuery = text.trim()
        results.clear()
        offset = 0
        endReached = false
        pageFailed = false
        status = if (GiphyClient.hasApiKey) Status.LOADING else Status.MISSING_KEY
        changed()
        if (status == Status.MISSING_KEY) return
        loadPage(scope)
    }

    /** Next page of results, when the grid or strip scrolls near the end. */
    fun loadMore() {
        val scope = scope ?: return
        if (restricted || endReached || pageFailed || searchJob?.isActive == true || results.isEmpty()) return
        if (!GiphyClient.hasApiKey) return
        loadPage(scope)
        changed()
    }

    private fun loadPage(scope: CoroutineScope) {
        val forQuery = activeQuery
        val pageOffset = offset
        searchJob = scope.launch {
            try {
                val page = withContext(Dispatchers.Default) { GiphyClient.fetch(forQuery.ifBlank { null }, pageOffset, language) }
                if (forQuery != activeQuery) return@launch
                val known = results.mapTo(HashSet()) { it.id }
                results.addAll(page.gifs.filter { known.add(it.id) })
                offset = pageOffset + GifConfig.PAGE_SIZE
                if (page.gifs.isEmpty() || offset >= GifConfig.MAX_RESULTS || (page.totalCount in 0..offset)) endReached = true
                status = if (results.isEmpty()) Status.EMPTY else Status.LOADED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (forQuery != activeQuery) return@launch
                Log.w(TAG, "loading GIFs failed", e)
                if (results.isEmpty()) status = Status.ERROR else pageFailed = true
            }
            changed()
        }
    }

    // ---------------------------------------------------------------- typing into the search field

    fun startTyping() {
        if (restricted || mode != Mode.BROWSE) return
        mode = Mode.TYPING
        host?.showGifTyping()
        changed()
    }

    /** Search key, enter, or the grid button: run the search now and show the grid. */
    fun finishTyping() {
        if (mode != Mode.TYPING) return
        mode = Mode.BROWSE
        if (query.trim() != activeQuery || debounceJob?.isActive == true) search(query)
        host?.showGifBrowse()
        changed()
    }

    fun typeText(text: String) {
        if (mode != Mode.TYPING || text.isEmpty()) return
        if (query.length + text.length > MAX_QUERY_LENGTH) return
        query += text.replace('\n', ' ')
        queryEdited()
    }

    fun backspace() {
        if (mode != Mode.TYPING || query.isEmpty()) return
        query = query.substring(0, query.offsetByCodePoints(query.length, -1))
        queryEdited()
    }

    fun deleteWord() {
        if (mode != Mode.TYPING || query.isEmpty()) return
        query = query.trimEnd().let { it.substring(0, it.lastIndexOf(' ') + 1) }
        queryEdited()
    }

    fun clearQuery() {
        if (query.isEmpty()) return
        query = ""
        if (mode == Mode.TYPING) queryEdited() else search("")
    }

    /** Search-as-you-type: wait for a pause in typing, and drop the request that's now outdated. */
    private fun queryEdited() {
        val scope = scope ?: return
        searchJob?.cancel()
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(GifConfig.SEARCH_DEBOUNCE_MS)
            debounceJob = null
            search(query)
        }
        changed()
    }

    // ---------------------------------------------------------------- sending

    fun send(gif: GiphyClient.Gif) {
        val scope = scope ?: return
        val listener = actionListener ?: return
        if (sending) return
        sending = true
        changed()
        scope.launch {
            sender.sendGif(gif, editorInfo, listener) { deleteTypedWordsIfSearchedFromThem() }
            recentStore.add(gif)
            sending = false
            requestClose()
        }
    }

    fun send(recipe: StickerRecipe) {
        val scope = scope ?: return
        val listener = actionListener ?: return
        if (sending) return
        sending = true
        changed()
        scope.launch {
            val asImage = sender.sendSticker(recipe, editorInfo, listener) { deleteTypedWordsIfSearchedFromThem() }
            if (BuildConfig.DEBUG) Log.d(TAG, "sticker sent ${if (asImage) "as image" else "as text"}")
            sending = false
            requestClose()
        }
    }

    /** A GIF picked from the words you typed replaces those words. */
    private fun deleteTypedWordsIfSearchedFromThem() {
        if (GifConfig.REPLACE_TYPED_QUERY && typedQuery.deleteLength > 0 && activeQuery == typedQuery.text)
            deleter?.delete(typedQuery.deleteLength)
    }

    fun clearRecents() {
        recentStore.clear()
        recents = emptyList()
        changed()
    }

    companion object {
        private const val TAG = "GifController"
        private const val MAX_QUERY_LENGTH = 50
    }
}
