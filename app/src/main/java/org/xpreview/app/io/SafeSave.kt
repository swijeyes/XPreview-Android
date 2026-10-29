// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.io

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.xpreview.pdf.DocId
import org.xpreview.pdf.OpenResult
import org.xpreview.pdf.PdfEngine
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

class SaveException(message: String) : IOException(message)

/**
 * Saving safely (plan 7.4): write a temp file in the app cache, verify it by
 * reopening it with PDFium (page count and first-page render), then copy it
 * to the destination. A failed save never touches the destination.
 */
object SafeSave {
    fun workDir(context: Context): File = File(context.cacheDir, "work").apply { mkdirs() }

    fun tempFile(context: Context, suffix: String = ".pdf"): File = File(workDir(context), UUID.randomUUID().toString() + suffix)

    /** Writes [doc] to a verified temp file. Caller owns (and deletes) the file. */
    fun writeVerified(context: Context, engine: PdfEngine, doc: DocId, incremental: Boolean = false): File {
        val expected = engine.pageCount(doc)
        val tmp = tempFile(context)
        val ok = ParcelFileDescriptor.open(
            tmp, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
        ).use { engine.save(doc, it, incremental) }
        if (!ok || !verify(engine, tmp, expected)) {
            tmp.delete()
            throw SaveException("The PDF could not be written correctly. Nothing was saved.")
        }
        return tmp
    }

    fun verify(engine: PdfEngine, file: File, expectedPages: Int): Boolean =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            when (val r = engine.open(fd)) {
                is OpenResult.Ok -> try {
                    val sizes = engine.pageSizes(r.doc)
                    r.info.pageCount == expectedPages && (expectedPages == 0 || run {
                        val bmp = Bitmap.createBitmap(32, (32 * sizes[0].height / sizes[0].width).toInt().coerceIn(8, 128), Bitmap.Config.ARGB_8888)
                        val rendered = engine.render(r.doc, 0, bmp, 0, 0, 32f / sizes[0].width, 0, 0)
                        bmp.recycle()
                        rendered
                    })
                } finally {
                    engine.close(r.doc)
                }
                else -> false
            }
        }

    /** Copies a verified file to [dest] ("wt": truncate). Keeps [file]. */
    fun copyTo(context: Context, file: File, dest: Uri) {
        val out = context.contentResolver.openOutputStream(dest, "wt") ?: throw SaveException("The destination cannot be written.")
        out.use { o -> FileInputStream(file).use { it.copyTo(o) } }
    }

    /** Byte-for-byte copy of an unchanged document (rule carried over from desktop). */
    fun copyUri(context: Context, src: Uri, dest: Uri) {
        val input = context.contentResolver.openInputStream(src) ?: throw SaveException("The original cannot be read.")
        val out = context.contentResolver.openOutputStream(dest, "wt") ?: throw SaveException("The destination cannot be written.")
        input.use { i -> out.use { i.copyTo(it) } }
    }

    fun canWrite(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
    }.getOrDefault(false)
}
