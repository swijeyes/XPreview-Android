// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.io

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.xpreview.common.SniffedFormat
import org.xpreview.image.ImageLoader
import org.xpreview.pdf.DocId
import org.xpreview.pdf.ImageLayout
import org.xpreview.pdf.PdfEngine
import org.xpreview.vision.Geometry
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

enum class PageSizeOption(val label: String) { Fit("Fit to image"), A4("A4"), Letter("Letter") }

/** Plan 7.2 quality presets: JPEG quality and the longest side in pixels. */
enum class Quality(val label: String, val maxSide: Int, val jpegQuality: Int) {
    Original("Original", Int.MAX_VALUE, 95), High("High", 3508, 85), Small("Small", 2000, 65),
}

data class ExportOptions(
    val pageSize: PageSizeOption = PageSizeOption.Fit,
    val marginPt: Float = 0f,
    val quality: Quality = Quality.High,
    val ocr: Boolean = false,
)

/** Builds PDF pages from images (plan 7.1 / 7.3). Blocking; call on the PDF dispatcher. */
class PdfBuilder(private val context: Context, private val engine: PdfEngine) {

    /**
     * Adds [uri] as a new page. JPEGs are embedded without recompression when
     * the preset allows it (EXIF rotation becomes the page's /Rotate, and
     * EXIF/GPS metadata is stripped); other formats are decoded and either
     * stored losslessly or JPEG-encoded per preset.
     */
    fun addImage(doc: DocId, at: Int, uri: Uri, options: ExportOptions) {
        val format = ImageLoader.sniff(context, uri)
        if (format == SniffedFormat.Jpeg && options.quality == Quality.Original) {
            val orientation = ImageLoader.exifOrientation(context, uri)
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> 0
                ExifInterface.ORIENTATION_ROTATE_90 -> 1
                ExifInterface.ORIENTATION_ROTATE_180 -> 2
                ExifInterface.ORIENTATION_ROTATE_270 -> 3
                else -> -1  // mirrored: needs real pixels
            }
            val bytes = if (rotation >= 0) readLimited(uri) else null
            val clean = bytes?.let { JpegUtils.stripMetadata(it) }
            val size = clean?.let { JpegUtils.size(it) }
            if (clean != null && size != null) {
                // Lay out in stored orientation, then rotate the page losslessly.
                val (w, h) = size
                val layout = layout(w, h, options, rotated = rotation % 2 == 1)
                if (!engine.addJpegPage(doc, at, clean, layout)) throw IOException("The JPEG could not be embedded.")
                if (rotation != 0) engine.setRotation(doc, at, rotation)
                return
            }
        }
        val img = ImageLoader.decode(context, uri, maxPixels = pixelBudget(options.quality), format = format)
        try {
            addBitmap(doc, at, img.bitmap, options, lossless = options.quality == Quality.Original && format != SniffedFormat.Jpeg)
        } finally {
            img.bitmap.recycle()
        }
    }

    fun addBitmap(doc: DocId, at: Int, bitmap: Bitmap, options: ExportOptions, lossless: Boolean, dpi: Float? = null) {
        val scaled = downscale(bitmap, options.quality.maxSide)
        try {
            val layout = layout(scaled.width, scaled.height, options, dpi = dpi)
            val ok = if (lossless) engine.addBitmapPage(doc, at, scaled, layout)
            else engine.addJpegPage(doc, at, encodeJpeg(scaled, options.quality.jpegQuality), layout)
            if (!ok) throw IOException("The image could not be added to the PDF.")
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Adds an already-encoded JPEG (scanner output), sized as a scan at ~300 DPI. */
    fun addScanJpeg(doc: DocId, at: Int, jpeg: ByteArray, w: Int, h: Int, options: ExportOptions) {
        val layout = layout(w, h, options, dpi = 300f, snapToPaper = options.pageSize == PageSizeOption.Fit)
        if (!engine.addJpegPage(doc, at, jpeg, layout)) throw IOException("The scanned page could not be added.")
    }

    /**
     * Page size and image placement in points. "Fit" makes the page the size
     * of the image (at [dpi], or a DPI that keeps large photos about A4-sized);
     * A4/Letter centre the image inside the margins, landscape for wide images.
     */
    fun layout(w: Int, h: Int, options: ExportOptions, rotated: Boolean = false, dpi: Float? = null, snapToPaper: Boolean = false): ImageLayout {
        val effDpi = dpi ?: max(96f, max(w, h) / 11.69f)
        if (options.pageSize == PageSizeOption.Fit) {
            if (snapToPaper) Geometry.snapPaper(w, h)?.let { paper ->
                val (pw, ph) = if (w > h) paper.heightPt to paper.widthPt else paper.widthPt to paper.heightPt
                return ImageLayout(pw, ph, 0f, 0f, pw, ph)
            }
            val m = options.marginPt
            val iw = w * 72f / effDpi
            val ih = h * 72f / effDpi
            return ImageLayout(iw + 2 * m, ih + 2 * m, m, m, iw, ih)
        }
        val (shortSide, longSide) = if (options.pageSize == PageSizeOption.A4) 595.28f to 841.89f else 612f to 792f
        // Orientation follows the image as it will be displayed.
        val landscape = if (rotated) h > w else w > h
        var pw = if (landscape) longSide else shortSide
        var ph = if (landscape) shortSide else longSide
        if (rotated) { val t = pw; pw = ph; ph = t }  // page is built in stored orientation
        val m = options.marginPt
        val s = min((pw - 2 * m) / w, (ph - 2 * m) / h)
        val iw = w * s
        val ih = h * s
        return ImageLayout(pw, ph, (pw - iw) / 2, (ph - ih) / 2, iw, ih)
    }

    private fun pixelBudget(q: Quality): Long =
        if (q.maxSide == Int.MAX_VALUE) 40_000_000L else q.maxSide.toLong() * q.maxSide

    private fun downscale(b: Bitmap, maxSide: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= maxSide) return b
        val s = maxSide.toFloat() / long
        return Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
    }

    private fun readLimited(uri: Uri): ByteArray? = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            total += r
            if (total > MAX_JPEG_BYTES) return null
            out.write(buf, 0, r)
        }
        out.toByteArray()
    }

    companion object {
        const val MAX_JPEG_BYTES = 200L * 1024 * 1024

        fun encodeJpeg(b: Bitmap, quality: Int): ByteArray {
            // JPEG has no alpha: flatten onto white first.
            val opaque = if (b.hasAlpha()) {
                Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also {
                    val c = android.graphics.Canvas(it)
                    c.drawColor(android.graphics.Color.WHITE)
                    c.drawBitmap(b, 0f, 0f, null)
                }
            } else b
            return ByteArrayOutputStream().use { out ->
                opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (opaque !== b) opaque.recycle()
                out.toByteArray()
            }
        }
    }
}
