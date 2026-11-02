// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.gif.GifController.Item
import helium314.keyboard.keyboard.gif.GifController.Status
import helium314.keyboard.keyboard.gif.sticker.StickerRecipe
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlin.math.max

/**
 * The GIF grid shown in place of the letter keys: a search box (tap it to type), quick-pick
 * chips, and a sectioned grid (Stickers / Recent / Trending or results). Renders [GifController]'s
 * state; never talks to the network itself.
 */
class GifPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), GifController.Listener {

    private var controller: GifController? = null
    private val colors = GifPanelColors()

    private val searchIcon = ImageView(context).apply { setImageResource(R.drawable.sym_keyboard_search_lxx) }
    private val searchText = TextView(context).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }
    private val clearButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_close)
        background = null
        contentDescription = context.getString(R.string.gif_clear_search)
    }
    private val searchBox = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(2), 0)
        addView(searchIcon, LayoutParams(dp(20), dp(20)))
        addView(searchText, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        addView(clearButton, LayoutParams(dp(36), dp(36)))
        isClickable = true
        contentDescription = context.getString(R.string.gif_search_hint)
    }
    private val closeButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_close)
        background = null
        contentDescription = context.getString(R.string.gif_close)
    }
    private val chipRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
    private val grid = RecyclerView(context)
    private val statusText = TextView(context).apply { gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
    private val retryButton = TextView(context).apply {
        text = context.getString(R.string.gif_retry)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        minHeight = dp(40)
        gravity = Gravity.CENTER
    }
    private val statusBox = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(statusText, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(retryButton, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }
    private val footer = TextView(context).apply {
        text = context.getString(R.string.gif_powered_by)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        gravity = Gravity.END
        setPadding(dp(8), 0, dp(8), dp(2))
    }
    private val layoutManager = GridLayoutManager(context, GifConfig.MIN_COLUMNS)
    private val adapter = GridAdapter()
    private var shownQuery: String? = null

    init {
        orientation = VERTICAL
        visibility = GONE
        val topBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), 0, 0)
            addView(searchBox, LayoutParams(0, dp(40), 1f))
            addView(closeButton, LayoutParams(dp(44), dp(44)))
        }
        addView(topBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(chipScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val middle = FrameLayout(context)
        middle.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        middle.addView(statusBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(middle, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(footer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.currentList.getOrNull(position) is Item.GifItem) 1 else layoutManager.spanCount
        }
        grid.layoutManager = layoutManager
        grid.adapter = adapter
        grid.setHasFixedSize(true)
        grid.itemAnimator = null // items only ever appear; animating them just costs frames
        grid.setPadding(dp(2), 0, dp(2), 0)
        grid.clipToPadding = false
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - layoutManager.spanCount * 3)
                    controller?.loadMore()
            }
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // decoding new GIFs mid-fling causes jank; catch up when the list settles
                val glide = GifImages.glide(context)
                if (newState == RecyclerView.SCROLL_STATE_SETTLING) glide.pauseRequests() else glide.resumeRequests()
            }
        })

        closeButton.setOnClickListener { controller?.requestClose() }
        searchBox.setOnClickListener { controller?.startTyping() }
        clearButton.setOnClickListener { controller?.clearQuery() }
        retryButton.setOnClickListener { controller?.retry() }
    }

    fun bind(controller: GifController) {
        this.controller?.removeListener(this)
        this.controller = controller
        controller.addListener(this)
    }

    /** Called when the panel opens: theme colors may have changed since last time. */
    fun applyTheme() {
        colors.load()
        searchBox.background = colors.rounded(colors.chip, dp(20))
        searchText.setTextColor(colors.text)
        statusText.setTextColor(colors.text)
        footer.setTextColor(colors.text)
        footer.alpha = 0.6f
        retryButton.background = colors.rounded(colors.chipSelected, dp(20))
        retryButton.setTextColor(colors.chipSelectedText)
        colors.tint(closeButton)
        colors.tint(clearButton)
        colors.tint(searchIcon)
        shownQuery = null
        adapter.notifyDataSetChanged()
    }

    override fun onGifStateChanged() {
        val c = controller ?: return
        if (!c.isOpen) {
            adapter.submitList(emptyList())
            shownQuery = null
            GifImages.glide(context).resumeRequests() // in case it closed mid-fling
            return
        }
        // search box
        if (c.restricted) {
            searchText.text = context.getString(R.string.gif_restricted_short)
            searchText.alpha = 0.6f
        } else if (c.query.isEmpty()) {
            searchText.text = context.getString(R.string.gif_search_hint)
            searchText.alpha = 0.6f
        } else {
            searchText.text = c.query
            searchText.alpha = 1f
        }
        searchBox.isEnabled = !c.restricted
        clearButton.visibility = if (c.query.isEmpty() || c.restricted) GONE else VISIBLE
        chipScroll.visibility = if (c.restricted) GONE else VISIBLE
        footer.visibility = if (c.restricted) INVISIBLE else VISIBLE
        buildChips(c)

        // grid
        val items = c.items()
        val newQuery = c.activeQuery
        val scrollToTop = shownQuery != newQuery
        shownQuery = newQuery
        adapter.submitList(items) { if (scrollToTop) grid.scrollToPosition(0) }

        // centered status when there's nothing to show (or while sending)
        val message = when {
            c.sending -> R.string.gif_sending
            items.isNotEmpty() -> null
            else -> when (c.status) {
                Status.LOADING -> R.string.gif_loading
                Status.EMPTY -> R.string.gif_no_results
                Status.ERROR -> R.string.gif_load_failed
                Status.MISSING_KEY -> R.string.gif_missing_key
                Status.RESTRICTED -> R.string.gif_restricted
                Status.LOADED -> null
            }
        }
        statusBox.visibility = if (message == null) GONE else VISIBLE
        statusBox.background = if (c.sending && items.isNotEmpty()) colors.rounded(colors.chip, dp(12)) else null
        if (message != null) statusText.setText(message)
        retryButton.visibility = if (!c.sending && items.isEmpty() && c.status == Status.ERROR) VISIBLE else GONE
    }

    private var chipsFor: String? = null

    private fun buildChips(c: GifController) {
        val typed = c.typedQuery.text
        if (chipsFor != typed) {
            chipsFor = typed
            chipRow.removeAllViews()
            if (typed.isNotEmpty()) chipRow.addView(makeChip("“$typed”", typed))
            chipRow.addView(makeChip(context.getString(R.string.gif_trending), ""))
            GifConfig.QUICK_PICKS.forEach { chipRow.addView(makeChip(it, it)) }
        }
        for (i in 0 until chipRow.childCount) {
            val chip = chipRow.getChildAt(i) as TextView
            val selected = chip.tag == c.activeQuery
            chip.background = colors.rounded(if (selected) colors.chipSelected else colors.chip, dp(16))
            chip.setTextColor(if (selected) colors.chipSelectedText else colors.text)
        }
    }

    private fun makeChip(label: String, query: String) = TextView(context).apply {
        text = label
        isSingleLine = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        tag = query
        setOnClickListener { controller?.selectQuery(query) }
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // phones get 3 columns, tablets / landscape / wide one-handed panels get more
        val columns = max(GifConfig.MIN_COLUMNS, (w / resources.displayMetrics.density / GifConfig.TILE_TARGET_DP).toInt())
        if (columns != layoutManager.spanCount) layoutManager.spanCount = columns
    }

    private inner class GridAdapter : ListAdapter<Item, RecyclerView.ViewHolder>(ItemDiff) {
        override fun getItemViewType(position: Int) = when (getItem(position)) {
            is Item.Header -> TYPE_HEADER
            is Item.GifItem -> TYPE_GIF
            is Item.Stickers -> TYPE_STICKERS
            is Item.Message -> TYPE_MESSAGE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            TYPE_HEADER -> object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(6), dp(8), dp(6), dp(2))
                isAllCaps = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }) {}
            TYPE_GIF -> TileHolder(GifTileView(parent.context))
            TYPE_STICKERS -> StickerRowHolder(LinearLayout(parent.context).apply {
                orientation = HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            else -> object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(12), dp(14), dp(12), dp(14))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is Item.Header -> (holder.itemView as TextView).apply {
                    setText(item.title)
                    setTextColor(colors.text)
                    alpha = 0.7f
                }
                is Item.GifItem -> (holder as TileHolder).bind(item.gif)
                is Item.Stickers -> (holder as StickerRowHolder).bind(item.recipes)
                is Item.Message -> (holder.itemView as TextView).apply {
                    val retry = if (item.retry) " · " + context.getString(R.string.gif_retry) else ""
                    text = context.getString(item.text) + retry
                    setTextColor(colors.text)
                    alpha = 0.8f
                    isClickable = item.retry
                    setOnClickListener(if (item.retry) View.OnClickListener { controller?.retry() } else null)
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            when (holder) {
                is TileHolder -> holder.unbind()
                is StickerRowHolder -> holder.unbind()
            }
        }
    }

    private inner class TileHolder(val tile: GifTileView) : RecyclerView.ViewHolder(tile) {
        fun bind(gif: GiphyClient.Gif) {
            tile.background = colors.rounded(colors.chip, dp(6))
            tile.contentDescription = gif.title
            tile.setOnClickListener { controller?.send(gif) }
            GifImages.loadPreview(tile, gif.previewUrl, null)
        }
        fun unbind() {
            tile.setOnClickListener(null)
            GifImages.clear(tile)
        }
    }

    private inner class StickerRowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        private val tiles = ArrayList<GifTileView>()

        fun bind(recipes: List<StickerRecipe>) {
            while (tiles.size < recipes.size) {
                val tile = GifTileView(row.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                tiles.add(tile)
                row.addView(tile, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            tiles.forEachIndexed { i, tile ->
                val recipe = recipes.getOrNull(i)
                tile.visibility = if (recipe == null) INVISIBLE else VISIBLE
                if (recipe == null) { GifImages.clear(tile); return@forEachIndexed }
                tile.background = colors.rounded(colors.chip, dp(6))
                tile.contentDescription = context.getString(R.string.gif_sticker_label, recipe.plainText)
                tile.setOnClickListener { controller?.send(recipe) }
                if (tile.tag != recipe) {
                    tile.tag = recipe
                    GifImages.loadSticker(tile, recipe, null)
                }
            }
        }

        fun unbind() {
            tiles.forEach { it.tag = null; it.setOnClickListener(null); GifImages.clear(it) }
        }
    }

    private object ItemDiff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_GIF = 1
        private const val TYPE_STICKERS = 2
        private const val TYPE_MESSAGE = 3
    }
}

/** A square, rounded image tile; its GIF stops animating when it's detached or hidden. */
class GifTileView(context: Context) : ImageView(context) {
    init {
        scaleType = ScaleType.CENTER_CROP
        clipToOutline = true
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val m = (2 * resources.displayMetrics.density).toInt()
            setMargins(m, m, m, m)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) super.onMeasure(heightMeasureSpec, heightMeasureSpec)
        else super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** Colors from the keyboard theme, read when the panel opens. */
internal class GifPanelColors {
    var text = 0
    var chip = 0
    var chipSelected = 0
    var chipSelectedText = 0

    fun load() {
        val colors = Settings.getValues().mColors
        text = colors.get(ColorType.KEY_TEXT)
        chip = colors.get(ColorType.KEY_BACKGROUND)
        chipSelected = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        chipSelectedText = colors.get(ColorType.ACTION_KEY_ICON)
    }

    fun tint(view: ImageView) = Settings.getValues().mColors.setColor(view, ColorType.KEY_ICON)

    fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(color)
    }
}
