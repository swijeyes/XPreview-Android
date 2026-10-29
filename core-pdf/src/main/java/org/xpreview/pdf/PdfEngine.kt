// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.ParcelFileDescriptor

/**
 * Everything the UI may do with a PDF (plan 2.5). The in-process
 * [PdfiumEngine] implements it today; a sandboxed implementation running in an
 * isolatedProcess service can replace it without touching callers.
 *
 * Calls are blocking; run them on [org.xpreview.pdf.PdfDispatchers.pdf].
 * Page-space coordinates are PDF user space (points, y up); page-space
 * [RectF]s are normalised so that top <= bottom numerically.
 */
interface PdfEngine {
    fun open(fd: ParcelFileDescriptor, password: String? = null): OpenResult
    fun create(): DocId
    fun close(doc: DocId)
    fun pageCount(doc: DocId): Int
    fun pageSizes(doc: DocId): List<PageSize>
    fun info(doc: DocId): DocumentInfo
    fun save(doc: DocId, out: ParcelFileDescriptor, incremental: Boolean = false): Boolean

    fun render(doc: DocId, page: Int, bitmap: Bitmap, srcX: Int, srcY: Int, scale: Float, rotation: Int, flags: Int): Boolean
    fun transform(doc: DocId, page: Int, rotation: Int): PageTransform?

    fun charCount(doc: DocId, page: Int): Int
    fun pageText(doc: DocId, page: Int): String
    fun charIndexAt(doc: DocId, page: Int, pt: PointF, tolerance: Double): Int
    fun textRects(doc: DocId, page: Int, start: Int, count: Int): List<RectF>
    fun search(doc: DocId, page: Int, query: String, matchCase: Boolean, wholeWord: Boolean): List<TextMatch>
    fun links(doc: DocId, page: Int): List<PdfLink>
    fun outline(doc: DocId): List<OutlineItem>

    fun deletePages(doc: DocId, pages: IntArray): Boolean
    fun movePages(doc: DocId, pages: IntArray, dest: Int): Boolean
    fun importPages(dst: DocId, src: DocId, pages: IntArray, at: Int): Boolean
    fun newPage(doc: DocId, at: Int, width: Float, height: Float): Boolean
    fun rotation(doc: DocId, page: Int): Int
    fun setRotation(doc: DocId, page: Int, rotation: Int)
    fun cropBox(doc: DocId, page: Int): RectF
    fun setCropBox(doc: DocId, page: Int, box: RectF)
    fun flattenPage(doc: DocId, page: Int): Boolean

    fun addJpegPage(doc: DocId, at: Int, jpeg: ByteArray, layout: ImageLayout): Boolean
    fun addBitmapPage(doc: DocId, at: Int, bitmap: Bitmap, layout: ImageLayout): Boolean
    fun addTextLayer(doc: DocId, page: Int, renderWidth: Int, renderHeight: Int, words: List<OcrWord>): Int

    fun annotations(doc: DocId, page: Int): List<AnnotInfo>
    fun annotationContents(doc: DocId, page: Int, index: Int): String
    fun removeAnnotation(doc: DocId, page: Int, index: Int): Boolean
    fun addMarkup(doc: DocId, page: Int, type: AnnotType, rects: List<RectF>, argb: Int, author: String?): Boolean
    fun addInk(doc: DocId, page: Int, strokes: List<List<PointF>>, argb: Int, width: Float, author: String?): Boolean
    fun addShape(doc: DocId, page: Int, type: AnnotType, rect: RectF, strokeArgb: Int, fillArgb: Int, width: Float, author: String?): Boolean
    fun addNote(doc: DocId, page: Int, at: PointF, text: String, argb: Int, author: String?): Boolean
    fun addImageStamp(doc: DocId, page: Int, rect: RectF, bitmap: Bitmap, author: String?): Boolean
    fun addTextStamp(doc: DocId, page: Int, rect: RectF, lines: List<String>, fontSize: Float, argb: Int, author: String?): Boolean

    fun setFormHighlight(doc: DocId, on: Boolean)
    fun formFieldAt(doc: DocId, page: Int, pt: PointF): FormField?
    fun formClick(doc: DocId, page: Int, pt: PointF): Boolean
    fun formSetText(doc: DocId, page: Int, pt: PointF, text: String): Boolean
    fun formSelectOption(doc: DocId, page: Int, pt: PointF, option: Int, selected: Boolean): Boolean
}

/** Where an image goes on a new page, in points. */
data class ImageLayout(
    val pageWidth: Float, val pageHeight: Float,
    val x: Float, val y: Float, val width: Float, val height: Float,
)

/** A recognised word with its box in the pixel space of the OCR'd render. */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
