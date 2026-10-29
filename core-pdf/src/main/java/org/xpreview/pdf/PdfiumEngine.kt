// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID

/** In-process PDFium. Phase 9 adds an isolated-process twin (plan 2.5). */
class PdfiumEngine private constructor() : PdfEngine {

    companion object {
        @Volatile private var instance: PdfiumEngine? = null

        private const val ERR_FILE = 2L
        private const val ERR_FORMAT = 3L
        private const val ERR_PASSWORD = 4L
        private const val ERR_SECURITY = 5L

        /**
         * Initialises PDFium once per process. Bundled fallback fonts are copied
         * out of the APK so PDFium can map non-embedded fonts the same way as
         * on desktop (plan 2.4); Android's system fonts come after them.
         */
        fun get(context: Context): PdfiumEngine = instance ?: synchronized(this) {
            instance ?: PdfiumEngine().also {
                PdfiumNative.nativeInit(arrayOf(installFonts(context).path, "/system/fonts"))
                instance = it
            }
        }

        private fun installFonts(context: Context): File {
            val dir = File(context.noBackupFilesDir, "fonts")
            val assets = context.assets
            val names = assets.list("fonts")?.filter { it.endsWith(".ttf") }.orEmpty()
            dir.mkdirs()
            for (name in names) {
                val target = File(dir, name)
                if (target.exists() && target.length() > 0) continue
                val tmp = File(dir, "$name.tmp")
                assets.open("fonts/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(target)
            }
            return dir
        }

        private fun newName() = AnnotInfo.NAME_PREFIX + UUID.randomUUID()
    }

    override fun open(fd: ParcelFileDescriptor, password: String?): OpenResult {
        val r = PdfiumNative.nativeOpen(fd.fd, password)
        if (r > 0) return OpenResult.Ok(DocId(r), info(DocId(r)))
        return when (-r) {
            ERR_PASSWORD -> if (password == null) OpenResult.NeedsPassword else OpenResult.WrongPassword
            ERR_FORMAT -> OpenResult.Corrupt
            ERR_SECURITY -> OpenResult.Unsupported
            ERR_FILE -> OpenResult.Failed("The file could not be read.")
            else -> OpenResult.Corrupt
        }
    }

    override fun create(): DocId {
        val r = PdfiumNative.nativeCreate()
        check(r > 0) { "PDFium could not create a document" }
        return DocId(r)
    }

    override fun close(doc: DocId) = PdfiumNative.nativeClose(doc.raw)
    override fun pageCount(doc: DocId) = PdfiumNative.nativePageCount(doc.raw)

    override fun pageSizes(doc: DocId): List<PageSize> {
        val a = PdfiumNative.nativePageSizes(doc.raw)
        return List(a.size / 2) { PageSize(a[it * 2], a[it * 2 + 1]) }
    }

    override fun info(doc: DocId): DocumentInfo {
        val i = PdfiumNative.nativeDocInfo(doc.raw)
        val meta = listOf("Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate")
            .associateWith { PdfiumNative.nativeMetaText(doc.raw, it) }
            .filterValues { it.isNotBlank() }
        return DocumentInfo(
            pageCount = pageCount(doc),
            fileVersion = i.getOrElse(0) { 0 },
            encrypted = i.getOrElse(1) { -1 } >= 0,
            formType = FormType.entries.getOrElse(i.getOrElse(2) { 0 }) { FormType.None },
            javascriptActions = i.getOrElse(3) { 0 }.coerceAtLeast(0),
            metadata = meta,
        )
    }

    override fun save(doc: DocId, out: ParcelFileDescriptor, incremental: Boolean) =
        PdfiumNative.nativeSave(doc.raw, out.fd, incremental)

    override fun render(doc: DocId, page: Int, bitmap: Bitmap, srcX: Int, srcY: Int, scale: Float, rotation: Int, flags: Int) =
        PdfiumNative.nativeRender(doc.raw, page, bitmap, srcX, srcY, scale, rotation, flags)

    override fun transform(doc: DocId, page: Int, rotation: Int): PageTransform? =
        PdfiumNative.nativePageTransform(doc.raw, page, rotation).takeIf { it.size == 6 }?.let(::PageTransform)

    override fun charCount(doc: DocId, page: Int) = PdfiumNative.nativeCharCount(doc.raw, page)
    override fun pageText(doc: DocId, page: Int) = PdfiumNative.nativePageText(doc.raw, page)

    override fun charIndexAt(doc: DocId, page: Int, pt: PointF, tolerance: Double) =
        PdfiumNative.nativeCharIndexAt(doc.raw, page, pt.x.toDouble(), pt.y.toDouble(), tolerance)

    override fun textRects(doc: DocId, page: Int, start: Int, count: Int): List<RectF> =
        ltrbRects(PdfiumNative.nativeTextRects(doc.raw, page, start, count), 0, Int.MAX_VALUE)

    override fun search(doc: DocId, page: Int, query: String, matchCase: Boolean, wholeWord: Boolean): List<TextMatch> {
        if (query.isEmpty()) return emptyList()
        val a = PdfiumNative.nativeSearch(doc.raw, page, query, matchCase, wholeWord)
        val out = ArrayList<TextMatch>()
        var i = 0
        while (i + 2 < a.size) {
            val n = a[i + 2].toInt()
            out += TextMatch(page, a[i].toInt(), a[i + 1].toInt(), ltrbRects(a, i + 3, n))
            i += 3 + n * 4
        }
        return out
    }

    /** Converts PDFium's [left, top, right, bottom] (y up) rects. */
    private fun ltrbRects(a: FloatArray, from: Int, count: Int): List<RectF> {
        val out = ArrayList<RectF>()
        var i = from
        while (i + 3 < a.size && out.size < count) {
            out += RectF(a[i], minOf(a[i + 1], a[i + 3]), a[i + 2], maxOf(a[i + 1], a[i + 3]))
            i += 4
        }
        return out
    }

    override fun links(doc: DocId, page: Int): List<PdfLink> {
        val r = PdfiumNative.nativeLinks(doc.raw, page)
        val rects = r[0] as FloatArray
        val dests = r[1] as IntArray
        @Suppress("UNCHECKED_CAST") val uris = r[2] as Array<String>
        return dests.indices.map { i ->
            val k = i * 4
            PdfLink(
                RectF(rects[k], minOf(rects[k + 1], rects[k + 3]), rects[k + 2], maxOf(rects[k + 1], rects[k + 3])),
                dests[i], uris[i].ifEmpty { null },
            )
        }
    }

    override fun outline(doc: DocId): List<OutlineItem> {
        val r = PdfiumNative.nativeOutline(doc.raw)
        @Suppress("UNCHECKED_CAST") val titles = r[0] as Array<String>
        val levels = r[1] as IntArray
        val pages = r[2] as IntArray
        return titles.indices.map { OutlineItem(titles[it].trim(), levels[it], pages[it]) }
    }

    override fun deletePages(doc: DocId, pages: IntArray) = PdfiumNative.nativeDeletePages(doc.raw, pages)
    override fun movePages(doc: DocId, pages: IntArray, dest: Int) = PdfiumNative.nativeMovePages(doc.raw, pages, dest)
    override fun importPages(dst: DocId, src: DocId, pages: IntArray, at: Int) =
        PdfiumNative.nativeImportPages(dst.raw, src.raw, pages, at)
    override fun newPage(doc: DocId, at: Int, width: Float, height: Float) =
        PdfiumNative.nativeNewPage(doc.raw, at, width, height)
    override fun rotation(doc: DocId, page: Int) = PdfiumNative.nativeGetRotation(doc.raw, page)
    override fun setRotation(doc: DocId, page: Int, rotation: Int) =
        PdfiumNative.nativeSetRotation(doc.raw, page, ((rotation % 4) + 4) % 4)

    override fun cropBox(doc: DocId, page: Int): RectF {
        val b = PdfiumNative.nativeGetCropBox(doc.raw, page)
        return RectF(minOf(b[0], b[2]), minOf(b[1], b[3]), maxOf(b[0], b[2]), maxOf(b[1], b[3]))
    }

    override fun setCropBox(doc: DocId, page: Int, box: RectF) =
        PdfiumNative.nativeSetCropBox(doc.raw, page, box.left, box.top, box.right, box.bottom)

    override fun flattenPage(doc: DocId, page: Int) = PdfiumNative.nativeFlattenPage(doc.raw, page)

    override fun addJpegPage(doc: DocId, at: Int, jpeg: ByteArray, layout: ImageLayout) = with(layout) {
        PdfiumNative.nativeAddJpegPage(doc.raw, at, jpeg, pageWidth, pageHeight, x, y, width, height)
    }

    override fun addBitmapPage(doc: DocId, at: Int, bitmap: Bitmap, layout: ImageLayout) = with(layout) {
        PdfiumNative.nativeAddBitmapPage(doc.raw, at, bitmap.asRgba(), pageWidth, pageHeight, x, y, width, height)
    }

    override fun addTextLayer(doc: DocId, page: Int, renderWidth: Int, renderHeight: Int, words: List<OcrWord>): Int {
        val boxes = IntArray(words.size * 4)
        words.forEachIndexed { i, w ->
            boxes[i * 4] = w.left; boxes[i * 4 + 1] = w.top; boxes[i * 4 + 2] = w.right; boxes[i * 4 + 3] = w.bottom
        }
        return PdfiumNative.nativeAddTextLayer(
            doc.raw, page, renderWidth, renderHeight, words.map { it.text }.toTypedArray(), boxes,
        )
    }

    override fun annotations(doc: DocId, page: Int): List<AnnotInfo> {
        val r = PdfiumNative.nativeAnnots(doc.raw, page)
        val types = r[0] as IntArray
        val rects = r[1] as FloatArray
        @Suppress("UNCHECKED_CAST") val names = r[2] as Array<String>
        return types.indices.map { i ->
            val k = i * 4
            AnnotInfo(
                i, AnnotType.of(types[i]),
                RectF(rects[k], minOf(rects[k + 1], rects[k + 3]), rects[k + 2], maxOf(rects[k + 1], rects[k + 3])),
                names[i],
            )
        }
    }

    override fun annotationContents(doc: DocId, page: Int, index: Int) =
        PdfiumNative.nativeAnnotContents(doc.raw, page, index)

    override fun removeAnnotation(doc: DocId, page: Int, index: Int) =
        PdfiumNative.nativeRemoveAnnot(doc.raw, page, index)

    override fun addMarkup(doc: DocId, page: Int, type: AnnotType, rects: List<RectF>, argb: Int, author: String?): Boolean {
        if (rects.isEmpty()) return false
        // QuadPoints order: upper-left, upper-right, lower-left, lower-right.
        val q = FloatArray(rects.size * 8)
        rects.forEachIndexed { i, r ->
            val k = i * 8
            q[k] = r.left; q[k + 1] = r.bottom; q[k + 2] = r.right; q[k + 3] = r.bottom
            q[k + 4] = r.left; q[k + 5] = r.top; q[k + 6] = r.right; q[k + 7] = r.top
        }
        return PdfiumNative.nativeAddMarkup(doc.raw, page, type.code, q, argb, newName(), author)
    }

    override fun addInk(doc: DocId, page: Int, strokes: List<List<PointF>>, argb: Int, width: Float, author: String?) =
        PdfiumNative.nativeAddInk(
            doc.raw, page,
            strokes.filter { it.isNotEmpty() }.map { s -> FloatArray(s.size * 2) { if (it % 2 == 0) s[it / 2].x else s[it / 2].y } }
                .toTypedArray(),
            argb, width, newName(), author,
        )

    override fun addShape(doc: DocId, page: Int, type: AnnotType, rect: RectF, strokeArgb: Int, fillArgb: Int, width: Float, author: String?) =
        PdfiumNative.nativeAddShape(
            doc.raw, page, type.code, rect.left, rect.top, rect.right, rect.bottom,
            strokeArgb, fillArgb, width, null, newName(), author,
        )

    override fun addNote(doc: DocId, page: Int, at: PointF, text: String, argb: Int, author: String?) =
        PdfiumNative.nativeAddShape(
            doc.raw, page, AnnotType.Text.code, at.x, at.y - 20f, at.x + 20f, at.y,
            argb, 0, 1f, text, newName(), author,
        )

    override fun addImageStamp(doc: DocId, page: Int, rect: RectF, bitmap: Bitmap, author: String?) =
        PdfiumNative.nativeAddStamp(
            doc.raw, page, rect.left, rect.top, rect.right, rect.bottom, bitmap.asRgba(), null, 0f, 0,
            newName(), author,
        )

    override fun addTextStamp(doc: DocId, page: Int, rect: RectF, lines: List<String>, fontSize: Float, argb: Int, author: String?) =
        PdfiumNative.nativeAddStamp(
            doc.raw, page, rect.left, rect.top, rect.right, rect.bottom, null, lines.toTypedArray(), fontSize, argb,
            newName(), author,
        )

    override fun setFormHighlight(doc: DocId, on: Boolean) = PdfiumNative.nativeSetFormHighlight(doc.raw, on)

    override fun formFieldAt(doc: DocId, page: Int, pt: PointF): FormField? {
        val r = PdfiumNative.nativeFormFieldAt(doc.raw, page, pt.x, pt.y) ?: return null
        val ints = r[0] as IntArray
        @Suppress("UNCHECKED_CAST") val options = r[3] as Array<String>
        val selected = r[4] as IntArray
        val rect = r[5] as FloatArray
        return FormField(
            type = FieldType.entries.getOrElse(ints[0].coerceAtLeast(0)) { FieldType.Unknown },
            flags = ints[1],
            value = r[1] as String,
            name = r[2] as String,
            options = options.toList(),
            selected = selected.toSet(),
            rect = RectF(rect[0], minOf(rect[1], rect[3]), rect[2], maxOf(rect[1], rect[3])),
            tapPoint = PointF(pt.x, pt.y),
        )
    }

    override fun formClick(doc: DocId, page: Int, pt: PointF) = PdfiumNative.nativeFormClick(doc.raw, page, pt.x, pt.y)
    override fun formSetText(doc: DocId, page: Int, pt: PointF, text: String) =
        PdfiumNative.nativeFormSetText(doc.raw, page, pt.x, pt.y, text)
    override fun formSelectOption(doc: DocId, page: Int, pt: PointF, option: Int, selected: Boolean) =
        PdfiumNative.nativeFormSelectOption(doc.raw, page, pt.x, pt.y, option, selected)

    private fun Bitmap.asRgba(): Bitmap =
        if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
}
