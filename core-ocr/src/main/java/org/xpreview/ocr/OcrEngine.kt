// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.ocr

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import org.xpreview.pdf.OcrWord
import java.io.Closeable
import java.io.IOException

data class OcrResult(val words: List<OcrWord>, val text: String, val meanConfidence: Int)

/**
 * One Tesseract instance (plan 8.1): LSTM engine only, automatic page
 * segmentation. Not thread-safe; use one instance per thread.
 */
class OcrEngine(languages: LanguageManager, langs: List<String>) : Closeable {
    private val api = TessBaseAPI()

    init {
        languages.ensureBundled()
        val spec = langs.filter { LanguageManager.isValidCode(it) }.ifEmpty { listOf("eng") }.joinToString("+")
        if (!api.init(languages.dataPath.path, spec, TessBaseAPI.OEM_LSTM_ONLY)) {
            api.recycle()
            throw IOException("Tesseract could not load the language data ($spec).")
        }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        // Scans and renders are made at ~300 DPI.
        api.setVariable("user_defined_dpi", "300")
    }

    /** Recognises [bitmap]; word boxes are in its pixel space. */
    fun recognize(bitmap: Bitmap): OcrResult {
        api.setImage(bitmap)
        val text = api.utF8Text.orEmpty()
        val words = ArrayList<OcrWord>()
        val it = api.resultIterator
        if (it != null) {
            try {
                it.begin()
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                do {
                    val w = it.getUTF8Text(level)?.trim().orEmpty()
                    if (w.isNotEmpty() && it.confidence(level) > 10f) {
                        val r = it.getBoundingRect(level)
                        if (r.width() > 0 && r.height() > 0) words += OcrWord(w, r.left, r.top, r.right, r.bottom)
                    }
                } while (it.next(level))
            } finally {
                it.delete()
            }
        }
        val conf = api.meanConfidence()
        api.clear()
        return OcrResult(words, text, conf)
    }

    fun stop() = api.stop()

    override fun close() = api.recycle()
}
