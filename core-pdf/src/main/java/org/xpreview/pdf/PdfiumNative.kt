// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.pdf

import android.graphics.Bitmap

/** Raw JNI surface. Only [PdfiumEngine] calls this; see jni_pdfium.cpp. */
internal object PdfiumNative {
    init {
        System.loadLibrary("pdfium")
        System.loadLibrary("xpreview_pdf")
    }

    @JvmStatic external fun nativeInit(fontDirs: Array<String>)
    @JvmStatic external fun nativeOpen(fd: Int, password: String?): Long
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeClose(id: Long)
    @JvmStatic external fun nativePageCount(id: Long): Int
    @JvmStatic external fun nativePageSizes(id: Long): FloatArray
    @JvmStatic external fun nativeDocInfo(id: Long): IntArray
    @JvmStatic external fun nativeMetaText(id: Long, tag: String): String
    @JvmStatic external fun nativeSave(id: Long, fd: Int, incremental: Boolean): Boolean

    @JvmStatic external fun nativeRender(
        id: Long, page: Int, bitmap: Bitmap, srcX: Int, srcY: Int, scale: Float, rotation: Int, flags: Int,
    ): Boolean
    @JvmStatic external fun nativePageTransform(id: Long, page: Int, rotation: Int): FloatArray

    @JvmStatic external fun nativeCharCount(id: Long, page: Int): Int
    @JvmStatic external fun nativePageText(id: Long, page: Int): String
    @JvmStatic external fun nativeCharIndexAt(id: Long, page: Int, x: Double, y: Double, tol: Double): Int
    @JvmStatic external fun nativeTextRects(id: Long, page: Int, start: Int, count: Int): FloatArray
    @JvmStatic external fun nativeSearch(id: Long, page: Int, query: String, matchCase: Boolean, wholeWord: Boolean): FloatArray

    @JvmStatic external fun nativeLinks(id: Long, page: Int): Array<Any>
    @JvmStatic external fun nativeOutline(id: Long): Array<Any>

    @JvmStatic external fun nativeDeletePages(id: Long, pages: IntArray): Boolean
    @JvmStatic external fun nativeMovePages(id: Long, pages: IntArray, dest: Int): Boolean
    @JvmStatic external fun nativeImportPages(dst: Long, src: Long, pages: IntArray, at: Int): Boolean
    @JvmStatic external fun nativeNewPage(id: Long, at: Int, w: Float, h: Float): Boolean
    @JvmStatic external fun nativeGetRotation(id: Long, page: Int): Int
    @JvmStatic external fun nativeSetRotation(id: Long, page: Int, rotation: Int)
    @JvmStatic external fun nativeGetCropBox(id: Long, page: Int): FloatArray
    @JvmStatic external fun nativeSetCropBox(id: Long, page: Int, l: Float, b: Float, r: Float, t: Float)
    @JvmStatic external fun nativeFlattenPage(id: Long, page: Int): Boolean

    @JvmStatic external fun nativeAddJpegPage(
        id: Long, at: Int, jpeg: ByteArray, w: Float, h: Float, x: Float, y: Float, iw: Float, ih: Float,
    ): Boolean
    @JvmStatic external fun nativeAddBitmapPage(
        id: Long, at: Int, bitmap: Bitmap, w: Float, h: Float, x: Float, y: Float, iw: Float, ih: Float,
    ): Boolean
    @JvmStatic external fun nativeAddTextLayer(
        id: Long, page: Int, devW: Int, devH: Int, words: Array<String>, boxes: IntArray,
    ): Int

    @JvmStatic external fun nativeAnnots(id: Long, page: Int): Array<Any>
    @JvmStatic external fun nativeAnnotContents(id: Long, page: Int, annot: Int): String
    @JvmStatic external fun nativeRemoveAnnot(id: Long, page: Int, annot: Int): Boolean
    @JvmStatic external fun nativeAddMarkup(
        id: Long, page: Int, subtype: Int, quads: FloatArray, argb: Int, nm: String, author: String?,
    ): Boolean
    @JvmStatic external fun nativeAddInk(
        id: Long, page: Int, strokes: Array<FloatArray>, argb: Int, width: Float, nm: String, author: String?,
    ): Boolean
    @JvmStatic external fun nativeAddShape(
        id: Long, page: Int, subtype: Int, l: Float, b: Float, r: Float, t: Float,
        strokeArgb: Int, fillArgb: Int, width: Float, contents: String?, nm: String, author: String?,
    ): Boolean
    @JvmStatic external fun nativeAddStamp(
        id: Long, page: Int, l: Float, b: Float, r: Float, t: Float, bitmap: Bitmap?, lines: Array<String>?,
        fontSize: Float, argb: Int, nm: String, author: String?,
    ): Boolean

    @JvmStatic external fun nativeSetFormHighlight(id: Long, on: Boolean)
    @JvmStatic external fun nativeFormFieldAt(id: Long, page: Int, x: Float, y: Float): Array<Any>?
    @JvmStatic external fun nativeFormClick(id: Long, page: Int, x: Float, y: Float): Boolean
    @JvmStatic external fun nativeFormSetText(id: Long, page: Int, x: Float, y: Float, text: String): Boolean
    @JvmStatic external fun nativeFormSelectOption(
        id: Long, page: Int, x: Float, y: Float, option: Int, selected: Boolean,
    ): Boolean
}
