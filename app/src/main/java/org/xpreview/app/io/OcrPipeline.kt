// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.io

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xpreview.common.Limits
import org.xpreview.ocr.LanguageManager
import org.xpreview.ocr.OcrEngine
import org.xpreview.ocr.OcrResult
import org.xpreview.pdf.DocId
import org.xpreview.pdf.PdfDispatchers
import org.xpreview.pdf.PdfEngine
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class OcrSummary(val pages: Int, val words: Int, val lowConfidencePages: List<Int>)

/**
 * OCR to searchable PDF (plan 8.3, text-layer method a): each page is
 * rendered at ~300 DPI, recognised by Tesseract, and an invisible text layer
 * is added on top of the existing page content, which stays untouched.
 */
class OcrPipeline(private val engine: PdfEngine, private val languages: LanguageManager) {

    /** Pages with no text at all: the candidates for "Make searchable" (plan 8.4). */
    suspend fun pagesWithoutText(doc: DocId): List<Int> = withContext(PdfDispatchers.pdf) {
        (0 until engine.pageCount(doc)).filter { engine.charCount(doc, it) == 0 }
    }

    suspend fun makeSearchable(
        doc: DocId,
        pages: List<Int>,
        langs: List<String>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): OcrSummary = withContext(Dispatchers.Default) {
        var words = 0
        val low = ArrayList<Int>()
        OcrEngine(languages, langs).use { ocr ->
            pages.forEachIndexed { i, page ->
                coroutineContext.ensureActive()
                onProgress(i, pages.size)
                val bmp = withContext(PdfDispatchers.pdf) { renderForOcr(doc, page) } ?: return@forEachIndexed
                val result = try { ocr.recognizeRendered(bmp) } finally { bmp.recycle() }
                coroutineContext.ensureActive()
                words += withContext(PdfDispatchers.pdf) { engine.addTextLayer(doc, page, result.width, result.height, result.result.words) }
                if (result.result.words.isNotEmpty() && result.result.meanConfidence < 60) low += page
            }
            onProgress(pages.size, pages.size)
        }
        OcrSummary(pages.size, words, low)
    }

    suspend fun recognize(bitmap: Bitmap, langs: List<String>): OcrResult = withContext(Dispatchers.Default) {
        OcrEngine(languages, langs).use { it.recognize(bitmap) }
    }

    private class Rendered(val width: Int, val height: Int, val result: OcrResult)

    private fun OcrEngine.recognizeRendered(b: Bitmap) = Rendered(b.width, b.height, recognize(b))

    private fun renderForOcr(doc: DocId, page: Int): Bitmap? {
        val size = engine.pageSizes(doc).getOrNull(page) ?: return null
        var scale = 300f / 72f
        val long = max(size.width, size.height) * scale
        if (long > Limits.MAX_SCAN_SIDE) scale *= Limits.MAX_SCAN_SIDE / long
        val w = max(1, (size.width * scale).roundToInt())
        val h = max(1, (size.height * scale).roundToInt())
        if (w.toLong() * h > MAX_OCR_PIXELS) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return if (engine.render(doc, page, bmp, 0, 0, min(scale, w / size.width), 0, 0)) bmp else { bmp.recycle(); null }
    }

    companion object { const val MAX_OCR_PIXELS = 20_000_000L }
}
