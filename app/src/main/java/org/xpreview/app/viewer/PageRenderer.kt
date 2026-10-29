// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.viewer

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xpreview.common.Limits
import org.xpreview.pdf.DocId
import org.xpreview.pdf.PdfDispatchers
import org.xpreview.pdf.PdfEngine
import kotlin.coroutines.coroutineContext

enum class RenderKind { Base, Tile, Thumb }

/**
 * Identifies one rendered bitmap. [scale] is pixels per point; tiles are
 * [TILE] px squares at (tx, ty) of the page rendered at that scale.
 */
data class RenderKey(
    val page: Int,
    val kind: RenderKind,
    val scale: Float,
    val tx: Int,
    val ty: Int,
    val width: Int,
    val height: Int,
    val version: Int,
    val rotation: Int,
    val flags: Int,
)

/**
 * Bitmap cache + request queue (plan 4.3): LRU sized from the memory class,
 * stale requests are cancelled when the visible set changes, and all
 * rendering runs on the single PDFium thread.
 */
class PageRenderer(
    private val engine: PdfEngine,
    private val doc: () -> DocId?,
    private val scope: CoroutineScope,
    cacheBytes: Int,
) {
    /** Bumped whenever a bitmap arrives; read by the drawing code. */
    val version = mutableIntStateOf(0)

    private val cache = object : LruCache<RenderKey, ImageBitmap>(cacheBytes) {
        override fun sizeOf(key: RenderKey, value: ImageBitmap) = value.width * value.height * 4
    }
    private val thumbs = object : LruCache<RenderKey, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: RenderKey, value: ImageBitmap) = value.width * value.height * 4
    }
    /** Last base bitmap per page, any version: shown while a re-render after an edit is pending. */
    private val lastBase = LruCache<Int, ImageBitmap>(16)
    private val jobs = HashMap<RenderKey, Job>()

    fun get(key: RenderKey): ImageBitmap? = if (key.kind == RenderKind.Thumb) thumbs.get(key) else cache.get(key)
    fun fallback(page: Int): ImageBitmap? = lastBase.get(page)

    /** Main thread. Starts missing renders in priority order; cancels the rest. */
    fun request(keys: List<RenderKey>, keepThumbs: Boolean = true) {
        val wanted = keys.toHashSet()
        val it = jobs.entries.iterator()
        while (it.hasNext()) {
            val (k, job) = it.next()
            if (k !in wanted && !(keepThumbs && k.kind == RenderKind.Thumb)) { job.cancel(); it.remove() }
        }
        for (k in keys) {
            if (get(k) != null || jobs.containsKey(k)) continue
            jobs[k] = scope.launch(PdfDispatchers.pdf) {
                val bmp = render(k)
                withContext(Dispatchers.Main) {
                    jobs.remove(k)
                    if (bmp != null) {
                        val img = bmp.asImageBitmap()
                        if (k.kind == RenderKind.Thumb) thumbs.put(k, img) else cache.put(k, img)
                        if (k.kind == RenderKind.Base) lastBase.put(k.page, img)
                        version.intValue++
                    }
                }
            }
        }
    }

    /** Requests thumbnails without cancelling viewer renders. */
    fun requestThumb(key: RenderKey) {
        if (thumbs.get(key) != null || jobs.containsKey(key)) return
        request(jobs.keys.toList() + key)
    }

    private suspend fun render(k: RenderKey): Bitmap? {
        coroutineContext.ensureActive()
        val d = doc() ?: return null
        if (k.width <= 0 || k.height <= 0 || k.width.toLong() * k.height > Limits.MAX_RENDER_PIXELS) return null
        val bmp = try {
            Bitmap.createBitmap(k.width, k.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            cache.trimToSize(cache.maxSize() / 2)
            return null
        }
        val ok = engine.render(d, k.page, bmp, k.tx * TILE, k.ty * TILE, k.scale, k.rotation, k.flags)
        if (!ok) { bmp.recycle(); return null }
        bmp.prepareToDraw()
        return bmp
    }

    /** After structural edits (pages moved/deleted) nothing cached is valid. */
    fun clearAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        cache.evictAll()
        thumbs.evictAll()
        lastBase.evictAll()
        version.intValue++
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    companion object { const val TILE = 512 }
}
