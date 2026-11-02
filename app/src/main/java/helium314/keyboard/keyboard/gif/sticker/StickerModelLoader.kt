// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif.sticker

import android.net.Uri
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.nio.ByteBuffer

/**
 * Lets Glide load `sticker://text?...` URIs: the sticker is rendered on Glide's background
 * threads as an animated GIF, then decoded, memory cached and disk cached like any downloaded GIF.
 * The cache key is the recipe's canonical URI, so each recipe is rendered once.
 */
class StickerModelLoader : ModelLoader<Uri, ByteBuffer> {
    override fun handles(model: Uri) = model.scheme == StickerRecipe.SCHEME

    override fun buildLoadData(model: Uri, width: Int, height: Int, options: Options): ModelLoader.LoadData<ByteBuffer>? {
        val recipe = StickerRecipe.parse(model.toString()) ?: return null
        return ModelLoader.LoadData(ObjectKey(recipe.toUriString()), StickerFetcher(recipe))
    }

    class Factory : ModelLoaderFactory<Uri, ByteBuffer> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Uri, ByteBuffer> = StickerModelLoader()
        override fun teardown() {}
    }
}

private class StickerFetcher(private val recipe: StickerRecipe) : DataFetcher<ByteBuffer> {
    @Volatile private var cancelled = false

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in ByteBuffer>) {
        try {
            val bytes = StickerRenderer.render(recipe, StickerRenderer.Format.GIF) { cancelled }
            callback.onDataReady(ByteBuffer.wrap(bytes))
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {}

    override fun cancel() {
        cancelled = true
    }

    override fun getDataClass(): Class<ByteBuffer> = ByteBuffer::class.java

    // REMOTE so Glide's default strategy also keeps the rendered bytes in its disk cache
    override fun getDataSource(): DataSource = DataSource.REMOTE
}
