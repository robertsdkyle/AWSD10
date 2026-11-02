// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.gif.GifController.Status
import helium314.keyboard.keyboard.gif.sticker.StickerRecipe
import helium314.keyboard.latin.R

/**
 * Shown above the letter keys while typing a GIF search: the search field (filled by the
 * keyboard, not by the app) and one row of live results, stickers first. Enter or the back
 * arrow returns to the full grid.
 */
class GifSearchStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), GifController.Listener {

    private var controller: GifController? = null
    private val colors = GifPanelColors()

    private val backButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_arrow_back)
        background = null
        contentDescription = context.getString(R.string.gif_back_to_grid)
    }
    private val queryText = TextView(context).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }
    private val caret = View(context)
    private val clearButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_close)
        background = null
        contentDescription = context.getString(R.string.gif_clear_search)
    }
    private val field = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(2), 0)
        addView(queryText, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f))
        addView(caret, LayoutParams(dp(2), dp(20)).apply { marginStart = dp(1) })
        addView(View(context), LayoutParams(0, 1, 1f))
        addView(clearButton, LayoutParams(dp(36), dp(36)))
    }
    private val results = RecyclerView(context)
    private val emptyText = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12), 0, dp(12), 0)
    }
    private val adapter = StripAdapter()
    private val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    private var shownQuery: String? = null
    private val blink = object : Runnable {
        override fun run() {
            caret.visibility = if (caret.visibility == VISIBLE) INVISIBLE else VISIBLE
            postDelayed(this, 530)
        }
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), dp(6), dp(2))
            addView(backButton, LayoutParams(dp(44), dp(40)))
            addView(field, LayoutParams(0, dp(38), 1f))
        }
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bottom = FrameLayout(context)
        bottom.addView(results, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bottom.addView(emptyText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TILE_DP + 8)))

        results.layoutManager = layoutManager
        results.adapter = adapter
        results.setHasFixedSize(true)
        results.itemAnimator = null
        results.setPadding(dp(4), dp(2), dp(4), dp(2))
        results.clipToPadding = false
        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 6) controller?.loadMore()
            }
        })

        backButton.setOnClickListener { controller?.finishTyping() }
        clearButton.setOnClickListener { controller?.clearQuery() }
        field.setOnClickListener { controller?.finishTyping() }
    }

    fun bind(controller: GifController) {
        this.controller?.removeListener(this)
        this.controller = controller
        controller.addListener(this)
    }

    fun applyTheme() {
        colors.load()
        field.background = colors.rounded(colors.chip, dp(19))
        queryText.setTextColor(colors.text)
        emptyText.setTextColor(colors.text)
        caret.setBackgroundColor(colors.chipSelected)
        colors.tint(backButton)
        colors.tint(clearButton)
        adapter.notifyDataSetChanged()
    }

    override fun onGifStateChanged() {
        val c = controller ?: return
        if (!c.isTyping) {
            removeCallbacks(blink)
            if (!c.isOpen) {
                adapter.submitList(emptyList())
                shownQuery = null
            }
            return
        }
        if (c.query.isEmpty()) {
            queryText.text = context.getString(R.string.gif_search_hint)
            queryText.alpha = 0.6f
        } else {
            queryText.text = c.query
            queryText.alpha = 1f
        }
        clearButton.visibility = if (c.query.isEmpty()) INVISIBLE else VISIBLE
        removeCallbacks(blink)
        caret.visibility = VISIBLE
        postDelayed(blink, 530)

        val items = c.stripItems()
        val scrollToStart = shownQuery != c.activeQuery
        shownQuery = c.activeQuery
        adapter.submitList(items) { if (scrollToStart) results.scrollToPosition(0) }
        val message = when {
            c.sending -> R.string.gif_sending
            items.isNotEmpty() -> null
            c.status == Status.LOADING -> R.string.gif_loading
            c.status == Status.ERROR -> R.string.gif_load_failed
            c.status == Status.MISSING_KEY -> R.string.gif_missing_key
            c.status == Status.EMPTY -> R.string.gif_no_results
            else -> R.string.gif_type_to_search
        }
        emptyText.visibility = if (message == null) GONE else VISIBLE
        if (message != null) emptyText.setText(message)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blink)
    }

    private inner class StripAdapter : ListAdapter<Any, Holder>(StripDiff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tile = GifTileView(parent.context)
            tile.layoutParams = RecyclerView.LayoutParams(dp(TILE_DP), dp(TILE_DP)).apply { setMargins(dp(2), 0, dp(2), 0) }
            return Holder(tile)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tile = holder.tile
            tile.background = colors.rounded(colors.chip, dp(6))
            when (val item = getItem(position)) {
                is StickerRecipe -> {
                    tile.scaleType = ImageView.ScaleType.FIT_CENTER
                    tile.contentDescription = context.getString(R.string.gif_sticker_label, item.plainText)
                    tile.setOnClickListener { controller?.send(item) }
                    GifImages.loadSticker(tile, item, null)
                }
                is GiphyClient.Gif -> {
                    tile.scaleType = ImageView.ScaleType.CENTER_CROP
                    tile.contentDescription = item.title
                    tile.setOnClickListener { controller?.send(item) }
                    GifImages.loadPreview(tile, item.previewUrl, null)
                }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.tile.setOnClickListener(null)
            GifImages.clear(holder.tile)
        }
    }

    private class Holder(val tile: GifTileView) : RecyclerView.ViewHolder(tile)

    private object StripDiff : DiffUtil.ItemCallback<Any>() {
        override fun areItemsTheSame(oldItem: Any, newItem: Any) = when {
            oldItem is GiphyClient.Gif && newItem is GiphyClient.Gif -> oldItem.id == newItem.id
            else -> oldItem == newItem
        }
        @android.annotation.SuppressLint("DiffUtilEquals") // both item kinds are data classes
        override fun areContentsTheSame(oldItem: Any, newItem: Any) = oldItem == newItem
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TILE_DP = 76
    }
}
