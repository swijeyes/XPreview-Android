// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.pdf

import android.graphics.PointF
import android.graphics.RectF

/** Opaque handle; only meaningful to the [PdfEngine] that produced it. */
@JvmInline value class DocId(val raw: Long)

sealed interface OpenResult {
    data class Ok(val doc: DocId, val info: DocumentInfo) : OpenResult
    data object NeedsPassword : OpenResult
    data object WrongPassword : OpenResult
    data object Corrupt : OpenResult
    data object Unsupported : OpenResult
    data class Failed(val message: String) : OpenResult
}

enum class FormType { None, AcroForm, XfaFull, XfaForeground }

data class DocumentInfo(
    val pageCount: Int,
    val fileVersion: Int,
    val encrypted: Boolean,
    val formType: FormType,
    val javascriptActions: Int,
    val metadata: Map<String, String>,
)

/** Display size in points, with the page's /Rotate applied. */
data class PageSize(val width: Float, val height: Float)

/** Rectangles are in PDF page space (y up). */
data class TextMatch(val page: Int, val start: Int, val count: Int, val rects: List<RectF>)

data class PdfLink(val rect: RectF, val destPage: Int, val uri: String?)

data class OutlineItem(val title: String, val level: Int, val page: Int)

enum class AnnotType(val code: Int) {
    Unknown(0), Text(1), Link(2), FreeText(3), Line(4), Square(5), Circle(6), Polygon(7), Polyline(8),
    Highlight(9), Underline(10), Squiggly(11), StrikeOut(12), Stamp(13), Caret(14), Ink(15), Popup(16),
    FileAttachment(17), Sound(18), Widget(20), Redact(28);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

data class AnnotInfo(val index: Int, val type: AnnotType, val rect: RectF, val name: String) {
    /** Annotations this app created carry an /NM starting with this prefix. */
    val isOwn: Boolean get() = name.startsWith(NAME_PREFIX)
    companion object { const val NAME_PREFIX = "xpreview-" }
}

enum class FieldType { Unknown, PushButton, CheckBox, RadioButton, ComboBox, ListBox, Text, Signature }

data class FormField(
    val type: FieldType,
    val flags: Int,
    val value: String,
    val name: String,
    val options: List<String>,
    val selected: Set<Int>,
    val rect: RectF,
    val tapPoint: PointF,
) {
    val readOnly get() = flags and 1 != 0
    val multiline get() = type == FieldType.Text && flags and (1 shl 12) != 0
    val password get() = type == FieldType.Text && flags and (1 shl 13) != 0
    val multiSelect get() = type == FieldType.ListBox && flags and (1 shl 21) != 0
}

/**
 * Affine map from normalised display coordinates (0..1 across the page as
 * shown, after rotation) to PDF page space, and back.
 */
class PageTransform(private val m: FloatArray) {
    fun toPage(nx: Float, ny: Float) = PointF(m[0] + nx * m[2] + ny * m[4], m[1] + nx * m[3] + ny * m[5])

    fun toNormalized(px: Float, py: Float): PointF {
        val det = m[2] * m[5] - m[4] * m[3]
        val dx = px - m[0]
        val dy = py - m[1]
        return PointF((dx * m[5] - dy * m[4]) / det, (m[2] * dy - m[3] * dx) / det)
    }

    /** Page-space rect to a normalised display rect. */
    fun toNormalized(r: RectF): RectF {
        val a = toNormalized(r.left, r.top)
        val b = toNormalized(r.right, r.bottom)
        return RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    /** Normalised display rect to a page-space rect (left, bottom, right, top order in RectF). */
    fun toPage(r: RectF): RectF {
        val a = toPage(r.left, r.top)
        val b = toPage(r.right, r.bottom)
        return RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }
}

object RenderFlags {
    const val ANNOTATIONS = 1
    const val GRAYSCALE = 2
    const val FORMS = 4
}
