// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.viewer

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileOutputStream

/** Prints a PDF file through the system print framework (desktop has printing too). */
object PdfPrint {
    fun print(context: Context, file: File, name: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(name, object : PrintDocumentAdapter() {
            override fun onLayout(
                old: PrintAttributes?, new: PrintAttributes, signal: CancellationSignal?,
                callback: LayoutResultCallback, extras: Bundle?,
            ) {
                if (signal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(
                    PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true,
                )
            }

            override fun onWrite(
                pages: Array<out PageRange>, destination: ParcelFileDescriptor, signal: CancellationSignal?,
                callback: WriteResultCallback,
            ) {
                try {
                    file.inputStream().use { i -> FileOutputStream(destination.fileDescriptor).use { i.copyTo(it) } }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }, null)
    }
}
