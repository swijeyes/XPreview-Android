// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app

import android.app.Application
import android.content.Context
import android.net.Uri
import org.xpreview.app.data.Recents
import org.xpreview.app.data.Settings
import org.xpreview.app.scan.ScanRepository
import org.xpreview.ocr.LanguageManager
import org.xpreview.pdf.PdfEngine
import org.xpreview.pdf.PdfiumEngine

/** Manual dependency container; small enough that a DI framework would only add weight. */
class AppContainer(val context: Context) {
    val engine: PdfEngine by lazy { PdfiumEngine.get(context) }
    val settings by lazy { Settings(context) }
    val recents by lazy { Recents(context) }
    val languages by lazy { LanguageManager(context) }
    val scans by lazy { ScanRepository(context) }

    /** Hand-off for screens that receive several URIs (navigation args carry one). */
    @Volatile var pendingImages: List<Uri> = emptyList()
}

class XPreviewApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Leftover temp files from a crash or a cancelled save.
        cacheDir.resolve("work").deleteRecursively()
        cacheDir.resolve("share").deleteRecursively()
    }
}

val Context.container: AppContainer get() = (applicationContext as XPreviewApp).container
