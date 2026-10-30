// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import android.content.ClipDescription
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

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

class GifPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chipRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
    private val closeButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_close)
        background = null
        contentDescription = context.getString(R.string.gif_close)
    }
    private val grid = RecyclerView(context)
    private val status = TextView(context).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(16)) }
    private val footer = TextView(context).apply {
        text = context.getString(R.string.gif_powered_by)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        gravity = Gravity.END
        setPadding(dp(8), 0, dp(8), dp(2))
    }
    private val adapter = GifAdapter()

    private var listener: KeyboardActionListener? = null
    private var editorInfo: EditorInfo? = null
    private var deleter: GifTextDeleter? = null
    private var onClose: Runnable? = null
    private var typedQuery = GifQuery.EMPTY

    private var currentQuery: String? = null // null means trending
    private var currentFromTyped = false
    private var offset = 0
    private var loading = false
    private var endReached = false
    private var generation = 0
    private var sending = false

    private var textColor = 0
    private var chipColor = 0
    private var chipSelectedColor = 0
    private var chipSelectedText = 0

    init {
        orientation = VERTICAL
        visibility = GONE
        val topBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chipScroll, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeButton, LayoutParams(dp(44), dp(44)))
        }
        addView(topBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val middle = FrameLayout(context)
        middle.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        middle.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(middle, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(footer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val layoutManager = GridLayoutManager(context, COLUMNS)
        grid.layoutManager = layoutManager
        grid.adapter = adapter
        grid.setPadding(dp(2), 0, dp(2), 0)
        grid.clipToPadding = false
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - COLUMNS * 3) loadMore()
            }
        })
        closeButton.setOnClickListener { onClose?.run() }
    }

    fun start(query: GifQuery, editorInfo: EditorInfo?, listener: KeyboardActionListener,
              deleter: GifTextDeleter, onClose: Runnable) {
        this.typedQuery = query
        this.editorInfo = editorInfo
        this.listener = listener
        this.deleter = deleter
        this.onClose = onClose
        sending = false
        applyColors()
        buildChips()
        if (query.text.isNotEmpty()) select(query.text, true) else select(null, false)
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        generation++
        loading = false
        sending = false
        adapter.clear()
        listener = null
        editorInfo = null
        deleter = null
        onClose = null
    }

    private fun applyColors() {
        val colors = Settings.getValues().mColors
        textColor = colors.get(ColorType.KEY_TEXT)
        chipColor = colors.get(ColorType.KEY_BACKGROUND)
        chipSelectedColor = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        chipSelectedText = colors.get(ColorType.ACTION_KEY_ICON)
        status.setTextColor(textColor)
        footer.setTextColor(textColor)
        footer.alpha = 0.6f
        colors.setColor(closeButton, ColorType.KEY_ICON)
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        if (typedQuery.text.isNotEmpty())
            chipRow.addView(makeChip("“${typedQuery.text}”", typedQuery.text, true))
        chipRow.addView(makeChip(context.getString(R.string.gif_trending), null, false))
        GifConfig.QUICK_PICKS.forEach { chipRow.addView(makeChip(it, it, false)) }
    }

    private fun makeChip(label: String, query: String?, fromTyped: Boolean) = TextView(context).apply {
        text = label
        isSingleLine = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        tag = ChipTag(query, fromTyped)
        setOnClickListener { select(query, fromTyped) }
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }
    }

    private data class ChipTag(val query: String?, val fromTyped: Boolean)

    private fun select(query: String?, fromTyped: Boolean) {
        currentQuery = query
        currentFromTyped = fromTyped
        for (i in 0 until chipRow.childCount) {
            val chip = chipRow.getChildAt(i) as TextView
            val tag = chip.tag as ChipTag
            val selected = tag.query == query && tag.fromTyped == fromTyped
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (selected) chipSelectedColor else chipColor)
            }
            chip.setTextColor(if (selected) chipSelectedText else textColor)
        }
        generation++
        scope.coroutineContext.cancelChildren()
        sending = false
        adapter.clear()
        grid.scrollToPosition(0)
        offset = 0
        endReached = false
        loading = false
        loadMore()
    }

    private fun loadMore() {
        if (loading || endReached) return
        loading = true
        val gen = generation
        val query = currentQuery
        val lang = Settings.getValues().mLocale?.language ?: "en"
        if (adapter.itemCount == 0) showStatus(context.getString(R.string.gif_loading))
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { GiphyClient.fetch(query, offset, lang) } }
            if (gen != generation) return@launch
            loading = false
            result.onSuccess { list ->
                offset += GifConfig.PAGE_SIZE
                if (list.isEmpty() || offset >= GifConfig.MAX_RESULTS) endReached = true
                adapter.add(list)
                if (adapter.itemCount == 0) showStatus(context.getString(R.string.gif_no_results)) else showStatus(null)
            }.onFailure { e ->
                Log.w(TAG, "loading GIFs failed", e)
                endReached = true
                if (adapter.itemCount == 0) showStatus(context.getString(
                    if (e is GiphyClient.MissingKeyException) R.string.gif_missing_key else R.string.gif_load_failed))
            }
        }
    }

    private fun showStatus(text: String?) {
        status.text = text
        status.visibility = if (text == null) GONE else VISIBLE
    }

    private fun send(gif: GiphyClient.Gif) {
        if (sending) return
        sending = true
        showStatus(context.getString(R.string.gif_sending))
        val gen = generation
        scope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { saveForSending(gif) }.getOrNull() }
            if (gen != generation) return@launch
            sending = false
            val listener = listener ?: return@launch
            if (currentFromTyped && GifConfig.REPLACE_TYPED_QUERY && typedQuery.deleteLength > 0)
                deleter?.delete(typedQuery.deleteLength)
            val mimeTypes = editorInfo?.let { EditorInfoCompat.getContentMimeTypes(it) } ?: emptyArray()
            val appTakesGifs = mimeTypes.any { ClipDescription.compareMimeTypes("image/gif", it) }
            if (uri != null && appTakesGifs) {
                val description = ClipDescription(gif.title, arrayOf("image/gif"))
                listener.onContent(InputContentInfoCompat(uri, description, Uri.parse(gif.pageUrl)))
            } else {
                // this app can't take images from keyboards, so send the link instead
                listener.onTextInput(gif.sendUrl)
            }
            onClose?.run()
        }
    }

    private fun saveForSending(gif: GiphyClient.Gif): Uri {
        val dir = File(context.filesDir, GIF_DIR).apply { mkdirs() }
        // keep the folder small, apps only need the file for a moment
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        val bytes = runCatching { GiphyClient.download(gif.sendUrl, 8 * 1024 * 1024) }
            .getOrElse { GiphyClient.download(gif.fallbackSendUrl) }
        val file = File(dir, "gif_${gif.id.filter { it.isLetterOrDigit() }}.gif")
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, context.getString(R.string.clipboard_provider_authority), file)
    }

    private inner class GifAdapter : RecyclerView.Adapter<GifHolder>() {
        private val items = ArrayList<GiphyClient.Gif>()

        fun add(list: List<GiphyClient.Gif>) {
            val start = items.size
            val known = items.mapTo(HashSet()) { it.id }
            items.addAll(list.filter { known.add(it.id) })
            notifyItemRangeInserted(start, items.size - start)
        }

        fun clear() {
            val size = items.size
            items.clear()
            notifyItemRangeRemoved(0, size)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = GifHolder(SquareImageView(parent.context))

        override fun onBindViewHolder(holder: GifHolder, position: Int) {
            val gif = items[position]
            val image = holder.image
            image.setImageDrawable(null)
            image.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(chipColor) }
            image.tag = gif.previewUrl
            image.contentDescription = gif.title
            image.setOnClickListener { send(gif) }
            scope.launch {
                val drawable = PreviewLoader.load(gif.previewUrl, resources) ?: return@launch
                if (image.tag != gif.previewUrl) return@launch
                image.setImageDrawable(drawable)
                (drawable as? Animatable)?.start()
            }
        }

        override fun onViewRecycled(holder: GifHolder) {
            (holder.image.drawable as? Animatable)?.stop()
            holder.image.setImageDrawable(null)
            holder.image.tag = null
        }
    }

    private class GifHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

    private inner class SquareImageView(context: Context) : ImageView(context) {
        init {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }

    /** Downloads and decodes small preview GIFs, keeping recent downloads in memory. */
    private object PreviewLoader {
        private val cache = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ByteArray) = value.size
        }
        private val downloads = Semaphore(6)

        suspend fun load(url: String, resources: android.content.res.Resources): Drawable? = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = cache.get(url) ?: downloads.withPermit { GiphyClient.download(url, 3 * 1024 * 1024) }
                    .also { cache.put(url, it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                } else {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { BitmapDrawable(resources, it) }
                }
            }.getOrNull()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.coroutineContext.cancelChildren()
        generation++
        loading = false
        sending = false
    }

    companion object {
        private const val TAG = "GifPanelView"
        private const val COLUMNS = 3
        const val GIF_DIR = "gifs"
    }
}
