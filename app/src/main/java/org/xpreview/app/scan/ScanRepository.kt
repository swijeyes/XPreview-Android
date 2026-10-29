// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.xpreview.app.io.JpegUtils
import org.xpreview.app.io.PdfBuilder
import org.xpreview.common.Limits
import org.xpreview.common.SniffedFormat
import org.xpreview.image.ImageLoader
import org.xpreview.vision.DocumentDetector
import org.xpreview.vision.Enhancer
import org.xpreview.vision.Quad
import org.xpreview.vision.ScanFilter
import org.xpreview.vision.Vision
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/** One page: the untouched original plus non-destructive settings (plan 6.6/6.7). */
data class ScanPage(
    val id: String,
    val quad: Quad,
    val filter: ScanFilter,
    val rotation: Int = 0,
    val brightness: Int = 0,
    val contrast: Float = 1f,
)

data class ScanSessionSummary(val id: String, val pages: Int, val modified: Long)

class ScanSession(val id: String, val dir: File, val pages: MutableList<ScanPage>) {
    fun original(pageId: String) = File(dir, "orig_$pageId.jpg")
    fun preview(pageId: String) = File(dir, "prev_$pageId.jpg")
}

/**
 * Scan sessions in filesDir/scans/<uuid>/: original_N.jpg files, a JSON
 * manifest and cached previews. They survive process death; abandoned ones
 * are removed after [MAX_AGE_MS] or on explicit discard.
 */
class ScanRepository(private val context: Context) {
    private val root get() = File(context.filesDir, "scans").apply { mkdirs() }

    fun create(): ScanSession {
        val id = UUID.randomUUID().toString()
        return ScanSession(id, File(root, id).apply { mkdirs() }, mutableListOf()).also { save(it) }
    }

    fun load(id: String): ScanSession? {
        if (!ID.matches(id)) return null
        val dir = File(root, id)
        val manifest = File(dir, MANIFEST)
        if (!manifest.exists()) return null
        return runCatching {
            val a = JSONObject(manifest.readText()).getJSONArray("pages")
            val pages = MutableList(a.length()) { i ->
                val o = a.getJSONObject(i)
                val q = o.getJSONArray("quad")
                ScanPage(
                    id = o.getString("id"),
                    quad = Quad.fromList(List(8) { q.getDouble(it) }),
                    filter = ScanFilter.valueOf(o.optString("filter", ScanFilter.Document.name)),
                    rotation = o.optInt("rotation"),
                    brightness = o.optInt("brightness"),
                    contrast = o.optDouble("contrast", 1.0).toFloat(),
                )
            }.filter { File(dir, "orig_${it.id}.jpg").exists() }.toMutableList()
            ScanSession(id, dir, pages)
        }.getOrNull()
    }

    /** Atomic manifest write (temp + rename). */
    fun save(s: ScanSession) {
        val o = JSONObject().put("version", 1).put("pages", JSONArray().apply {
            s.pages.forEach { p ->
                put(JSONObject().put("id", p.id).put("quad", JSONArray(p.quad.toList())).put("filter", p.filter.name)
                    .put("rotation", p.rotation).put("brightness", p.brightness).put("contrast", p.contrast.toDouble()))
            }
        })
        val tmp = File(s.dir, "$MANIFEST.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(File(s.dir, MANIFEST))) throw IOException("Could not save the scan session.")
    }

    fun delete(id: String) { if (ID.matches(id)) File(root, id).deleteRecursively() }

    fun cleanupAndList(): List<ScanSessionSummary> {
        val now = System.currentTimeMillis()
        return root.listFiles().orEmpty().mapNotNull { dir ->
            val m = File(dir, MANIFEST)
            val age = now - m.lastModified()
            val s = load(dir.name)
            when {
                s == null || age > MAX_AGE_MS || (s.pages.isEmpty() && age > 60 * 60 * 1000L) -> { dir.deleteRecursively(); null }
                s.pages.isEmpty() -> null
                else -> ScanSessionSummary(s.id, s.pages.size, m.lastModified())
            }
        }.sortedByDescending { it.modified }
    }

    fun newPageId(): String = UUID.randomUUID().toString().take(8)

    /** Gallery import (plan 6.1): the picked image becomes an original JPEG without metadata. */
    fun importImage(s: ScanSession, uri: Uri): String {
        val id = newPageId()
        val target = s.original(id)
        val fmt = ImageLoader.sniff(context, uri)
        val bytes = if (fmt == SniffedFormat.Jpeg && ImageLoader.exifOrientation(context, uri) <= 1) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { JpegUtils.stripMetadata(it) }
        } else null
        if (bytes != null) target.writeBytes(bytes) else {
            val img = ImageLoader.decode(context, uri, maxPixels = 24_000_000, format = fmt)
            target.writeBytes(PdfBuilder.encodeJpeg(img.bitmap, 95))
            img.bitmap.recycle()
        }
        return id
    }

    /** Decodes a JPEG upright (EXIF applied) within a pixel budget. */
    fun decodeUpright(file: File, maxPixels: Long): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
            val px = info.size.width.toLong() * info.size.height
            if (px > maxPixels) {
                val f = sqrt(maxPixels.toDouble() / px)
                d.setTargetSize(max(1, (info.size.width * f).toInt()), max(1, (info.size.height * f).toInt()))
            }
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** Re-detects on the captured image (plan 6.3), falling back to [fallback], then the inset full frame. */
    fun detect(file: File, fallback: Quad?): Quad {
        Vision.init()
        val small = decodeUpright(file, 1_000_000)
        return try { DocumentDetector.detect(small) ?: fallback ?: Quad.full() } finally { small.recycle() }
    }

    /** Warp + rotate + filter (plan 6.5/6.6). [maxPixels] bounds the decoded original. */
    fun render(s: ScanSession, p: ScanPage, maxSide: Int, maxPixels: Long): Bitmap {
        Vision.init()
        val src = decodeUpright(s.original(p.id), maxPixels)
        val warped = Enhancer.warp(src, p.quad, maxSide)
        src.recycle()
        val rotated = Enhancer.rotate(warped, p.rotation)
        if (rotated !== warped) warped.recycle()
        val out = Enhancer.filter(rotated, p.filter, p.brightness, p.contrast)
        rotated.recycle()
        return out
    }

    /** Cached processed preview (~1200 px). */
    fun preview(s: ScanSession, p: ScanPage): Bitmap {
        val f = s.preview(p.id)
        if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return it }
        val b = render(s, p, 1200, 3_000_000)
        runCatching { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        return b
    }

    fun invalidatePreview(s: ScanSession, pageId: String) { s.preview(pageId).delete() }

    /** Full-resolution output for export. */
    fun renderForExport(s: ScanSession, p: ScanPage, maxSide: Int): Bitmap =
        render(s, p, maxSide.coerceAtMost(Limits.MAX_SCAN_SIDE), 24_000_000)

    companion object {
        private const val MANIFEST = "manifest.json"
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private val ID = Regex("^[0-9a-f-]{36}$")
    }
}
