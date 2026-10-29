// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.images

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.xpreview.app.container
import org.xpreview.app.io.ExportOptions
import org.xpreview.app.io.OcrPipeline
import org.xpreview.app.io.PdfBuilder
import org.xpreview.app.io.SafeSave
import org.xpreview.common.SniffedFormat
import org.xpreview.common.UriUtils
import org.xpreview.image.ImageLoader
import org.xpreview.pdf.PdfDispatchers

sealed interface ImageState {
    data object Loading : ImageState
    data class Ready(val image: ImageBitmap, val width: Int, val height: Int, val format: SniffedFormat) : ImageState
    data class Failed(val message: String) : ImageState
}

data class ImageItem(val uri: Uri, val name: String)

class ImagesViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val items = mutableStateListOf<ImageItem>()
    val states = mutableStateMapOf<Uri, ImageState>()
    private val rotations = HashMap<Uri, Int>()
    private val decodeLimit = Semaphore(2)

    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var recognized by mutableStateOf<String?>(null)
    private var job: Job? = null
    var settings = org.xpreview.app.data.AppSettings()
        private set

    init {
        items += c.pendingImages.map { ImageItem(it, UriUtils.meta(app, it).name) }
        c.pendingImages = emptyList()
        viewModelScope.launch { c.settings.flow.collect { settings = it } }
    }

    fun load(uri: Uri) {
        if (states[uri] != null) return
        states[uri] = ImageState.Loading
        viewModelScope.launch {
            states[uri] = decodeLimit.withPermit {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val d = ImageLoader.decode(getApplication(), uri)
                        val rotated = rotate(d.bitmap, rotations[uri] ?: 0)
                        ImageState.Ready(rotated.asImageBitmap(), d.sourceWidth, d.sourceHeight, d.format)
                    }.getOrElse { ImageState.Failed(it.message ?: "The image could not be opened.") }
                }
            }
        }
    }

    fun rotate(uri: Uri, delta: Int) {
        rotations[uri] = ((rotations[uri] ?: 0) + delta + 4) % 4
        states.remove(uri)
        load(uri)
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
    }

    fun remove(i: Int) { if (i in items.indices && items.size > 1) items.removeAt(i) }

    fun add(uris: List<Uri>) { items += uris.map { ImageItem(it, UriUtils.meta(getApplication(), it).name) } }

    private fun rotate(b: Bitmap, q: Int): Bitmap {
        if (q == 0) return b
        val m = Matrix().apply { postRotate(90f * q) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** "Copy text from image" (plan 8.5). */
    fun recognize(uri: Uri) {
        job = viewModelScope.launch {
            busy = "Recognising text…"
            try {
                val bmp = withContext(Dispatchers.IO) {
                    rotate(ImageLoader.decode(getApplication(), uri, maxPixels = 16_000_000).bitmap, rotations[uri] ?: 0)
                }
                val r = OcrPipeline(c.engine, c.languages).recognize(bmp, settings.ocrLanguages)
                recognized = r.text.trim().ifEmpty { "(No text found)" }
            } catch (e: Exception) {
                message = e.message ?: "OCR failed"
            } finally {
                busy = null
            }
        }
    }

    /** Image(s) → PDF (plan 7.3), optional OCR, verified save to [dest]. */
    fun exportPdf(dest: Uri, options: ExportOptions, onDone: (Uri) -> Unit) {
        val list = items.toList()
        job = viewModelScope.launch {
            busy = "Creating PDF…"
            progress = 0f
            val ctx = getApplication<Application>()
            val engine = c.engine
            try {
                val doc = withContext(PdfDispatchers.pdf) { engine.create() }
                try {
                    val builder = PdfBuilder(ctx, engine)
                    list.forEachIndexed { i, item ->
                        withContext(PdfDispatchers.pdf) {
                            builder.addImage(doc, i, item.uri, options)
                            val q = rotations[item.uri] ?: 0
                            if (q != 0) engine.setRotation(doc, i, engine.rotation(doc, i) + q)
                        }
                        progress = (i + 1f) / list.size * if (options.ocr) 0.5f else 1f
                    }
                    if (options.ocr) {
                        busy = "Recognising text…"
                        OcrPipeline(engine, c.languages).makeSearchable(doc, list.indices.toList(), settings.ocrLanguages) { d, t ->
                            progress = 0.5f + 0.5f * d / t
                        }
                    }
                    busy = "Saving…"
                    withContext(PdfDispatchers.pdf) {
                        val f = SafeSave.writeVerified(ctx, engine, doc)
                        try { SafeSave.copyTo(ctx, f, dest) } finally { f.delete() }
                    }
                } finally {
                    withContext(PdfDispatchers.pdf) { engine.close(doc) }
                }
                onDone(dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = "Cancelled"
            } catch (e: Exception) {
                message = e.message ?: "The PDF could not be created."
            } finally {
                busy = null
                progress = null
            }
        }
    }

    fun cancel() { job?.cancel() }
}
