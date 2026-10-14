package com.chethan616.clearpdf.presentation.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * First-page previews for the Home grid, rendered with the platform PdfRenderer (separate from
 * pdf-core's viewer, so it never contends with an open document) and kept in a small memory cache.
 */
object PdfThumbnails {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Two renders at a time is enough to fill a screen of cards without starving the UI. */
    private val renders = Semaphore(2)

    fun cached(uri: Uri, widthPx: Int): Bitmap? = cache.get(key(uri, widthPx))

    /** Null when the file can't be rendered (encrypted, damaged, provider refused). */
    suspend fun load(context: Context, uri: Uri, widthPx: Int): Bitmap? {
        cached(uri, widthPx)?.let { return it }
        return renders.withPermit {
            withContext(Dispatchers.IO) {
                runCatching { render(context, uri, widthPx) }.getOrNull()?.also { cache.put(key(uri, widthPx), it) }
            }
        }
    }

    private fun render(context: Context, uri: Uri, widthPx: Int): Bitmap? {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return fd.use {
            PdfRenderer(it).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val height = (page.height * widthPx.toFloat() / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun key(uri: Uri, widthPx: Int) = "$widthPx|$uri"
}
