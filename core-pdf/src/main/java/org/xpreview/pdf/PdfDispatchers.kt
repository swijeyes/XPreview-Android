// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.pdf

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object PdfDispatchers {
    /**
     * PDFium is serialised by a global lock in the native layer, so one
     * thread is enough and keeps render ordering predictable. Large stacks:
     * deeply nested (but bounded) PDF structures are parsed recursively.
     */
    val pdf: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "pdfium", 8L * 1024 * 1024).apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
}
