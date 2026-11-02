// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import helium314.keyboard.keyboard.gif.sticker.StickerModelLoader
import helium314.keyboard.keyboard.gif.sticker.StickerRecipe
import java.nio.ByteBuffer

/**
 * Glide setup for the GIF panel: a modest disk cache (a keyboard shouldn't hog storage) and the
 * sticker:// loader, so generated stickers load, cache and recycle exactly like GIPHY previews.
 *
 * Requests use the application context because an IME has no Activity lifecycle; views clear
 * their own requests when they're recycled or the panel closes.
 */
object GifImages {
    private const val DISK_CACHE_BYTES = 64L * 1024 * 1024
    @Volatile private var initialized = false

    fun glide(context: Context): RequestManager {
        val app = context.applicationContext
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    @Suppress("VisibleForTests") // the public way to configure Glide without annotation processing
                    Glide.init(app, GlideBuilder()
                        .setDiskCache(InternalCacheDiskCacheFactory(app, "gif_panel_cache", DISK_CACHE_BYTES)))
                    Glide.get(app).registry.prepend(Uri::class.java, ByteBuffer::class.java, StickerModelLoader.Factory())
                    initialized = true
                }
            }
        }
        return Glide.with(app)
    }

    /** Loads a small GIPHY preview into a grid tile. */
    fun loadPreview(view: ImageView, url: String, placeholder: Drawable?) {
        glide(view.context).asGif().load(url)
            .placeholder(placeholder)
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .into(view)
    }

    /** Renders (or reuses the cached) text sticker into a tile. */
    fun loadSticker(view: ImageView, recipe: StickerRecipe, placeholder: Drawable?) {
        glide(view.context).asGif().load(Uri.parse(recipe.toUriString()))
            .placeholder(placeholder)
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .into(view)
    }

    /** Stops the tile's animation and releases its frames. */
    fun clear(view: ImageView) {
        if (initialized) Glide.with(view.context.applicationContext).clear(view)
        view.setImageDrawable(null)
    }
}
