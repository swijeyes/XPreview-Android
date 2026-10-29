// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.caverock.androidsvg.SVG
import org.xpreview.common.FormatSniffer
import org.xpreview.common.Limits
import org.xpreview.common.SniffedFormat
import org.xpreview.common.UriUtils
import org.xpreview.common.isRasterImage
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

class DecodedImage(
    val bitmap: Bitmap,
    /** Size of the source before any downsampling, after EXIF orientation. */
    val sourceWidth: Int,
    val sourceHeight: Int,
    val format: SniffedFormat,
)

class ImageLoadException(message: String) : IOException(message)

/**
 * Safe image decoding (plan 5.2): dimensions are read before decoding, a
 * pixel budget is enforced and large images are sampled down. Never call on
 * the main thread.
 */
object ImageLoader {

    fun sniff(context: Context, uri: Uri): SniffedFormat = FormatSniffer.sniff(UriUtils.readHead(context, uri))

    fun decode(
        context: Context,
        uri: Uri,
        maxPixels: Long = Limits.MAX_DISPLAY_IMAGE_PIXELS,
        format: SniffedFormat = sniff(context, uri),
    ): DecodedImage = when {
        format == SniffedFormat.Svg -> decodeSvg(context, uri, maxPixels)
        format.isRasterImage -> decodeRaster(context, uri, maxPixels, format)
        format == SniffedFormat.Tiff -> throw ImageLoadException("TIFF images are not supported in this version.")
        else -> throw ImageLoadException("This file is not a supported image.")
    }

    private fun decodeRaster(context: Context, uri: Uri, maxPixels: Long, format: SniffedFormat): DecodedImage {
        var srcW = 0
        var srcH = 0
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                srcW = info.size.width
                srcH = info.size.height
                if (srcW <= 0 || srcH <= 0 || srcW > Limits.MAX_IMAGE_DIMENSION || srcH > Limits.MAX_IMAGE_DIMENSION)
                    throw ImageLoadException("The image is too large (${srcW} × ${srcH}).")
                val pixels = srcW.toLong() * srcH
                if (pixels > maxPixels) {
                    val f = sqrt(maxPixels.toDouble() / pixels)
                    decoder.setTargetSize(max(1, (srcW * f).toInt()), max(1, (srcH * f).toInt()))
                }
                // Software bitmaps: they are read back for PDF export and OCR.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
                decoder.setOnPartialImageListener { true }  // show what decoded of a truncated file
            }
        } catch (e: ImageLoadException) {
            throw e
        } catch (e: Exception) {
            throw ImageLoadException("The image could not be decoded.")
        } catch (e: OutOfMemoryError) {
            throw ImageLoadException("Not enough memory to open this image.")
        }
        return DecodedImage(bitmap, srcW, srcH, format)
    }

    private fun decodeSvg(context: Context, uri: Uri, maxPixels: Long): DecodedImage {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = input.readNBytesCompat(Limits.MAX_SVG_BYTES + 1)
            if (buf.size > Limits.MAX_SVG_BYTES) throw ImageLoadException("The SVG file is too large.")
            buf
        } ?: throw ImageLoadException("The file could not be read.")
        // No SVGExternalFileResolver is registered, so references to local
        // files and URLs are never followed (same rule as desktop).
        val svg = try {
            SVG.getFromString(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ImageLoadException("The SVG file is malformed.")
        }
        var w = svg.documentWidth
        var h = svg.documentHeight
        val vb = svg.documentViewBox
        if ((w <= 0 || h <= 0) && vb != null) { w = vb.width(); h = vb.height() }
        if (w <= 0 || h <= 0 || !w.isFinite() || !h.isFinite()) { w = 1024f; h = 1024f }
        // Render vectors at a comfortable resolution: at least 2048 px on the
        // long side, within the pixel budget.
        val longSide = max(w, h)
        var scale = max(1f, 2048f / longSide)
        val budget = sqrt(maxPixels.toDouble() / (w.toDouble() * h)).toFloat()
        scale = minOf(scale, budget)
        val bw = max(1, ceil(w * scale).toInt())
        val bh = max(1, ceil(h * scale).toInt())
        if (bw > Limits.MAX_IMAGE_DIMENSION || bh > Limits.MAX_IMAGE_DIMENSION)
            throw ImageLoadException("The SVG image is too large.")
        if (vb == null) svg.setDocumentViewBox(0f, 0f, w, h)
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        try {
            svg.renderToCanvas(canvas)
        } catch (e: Exception) {
            throw ImageLoadException("The SVG file could not be rendered.")
        }
        return DecodedImage(bitmap, bw, bh, SniffedFormat.Svg)
    }

    /** EXIF orientation of a JPEG (ExifInterface.ORIENTATION_*), or NORMAL. */
    fun exifOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /** Pixel size of a JPEG as stored (before EXIF orientation), without decoding it. */
    fun storedSize(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val r = read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (r < 0) break
            out.write(buf, 0, r)
            total += r
        }
        return out.toByteArray()
    }
}
