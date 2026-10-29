// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.xpreview.pdf.PageSize
import kotlin.math.max
import kotlin.math.min

/**
 * Continuous vertical layout of all pages at zoom 1, where the widest page
 * fills the viewport width minus margins. Coordinates are content pixels.
 */
class DocLayout(
    sizes: List<PageSize>,
    val rotation: Int,
    val viewportWidth: Float,
    private val gap: Float,
    margin: Float,
) {
    val pageCount = sizes.size
    /** Display sizes in points after view rotation. */
    val displaySizes: List<PageSize> = sizes.map { if (rotation % 2 == 1) PageSize(it.height, it.width) else it }
    /** Pixels per point at zoom 1. */
    val basePxPerPt: Float
    val rects: List<Rect>
    val contentHeight: Float

    init {
        val maxW = displaySizes.maxOfOrNull { it.width } ?: 612f
        basePxPerPt = max(0.01f, (viewportWidth - 2 * margin) / maxW)
        var y = gap
        rects = displaySizes.map { s ->
            val w = s.width * basePxPerPt
            val h = s.height * basePxPerPt
            Rect((viewportWidth - w) / 2, y, (viewportWidth + w) / 2, y + h).also { y += h + gap }
        }
        contentHeight = y
    }

    /** Index of the page at content y (zoom 1), clamped. */
    fun pageAt(y: Float): Int {
        if (rects.isEmpty()) return 0
        var lo = 0
        var hi = rects.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (rects[mid].top - gap / 2 <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun gapPx() = gap
}

/** Zoom and scroll position. Offsets are content pixels at the current zoom. */
@Stable
class Viewport {
    var zoom by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var width by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)

    val minZoom = 0.25f
    var maxZoom = 12f

    fun clamp(layout: DocLayout) {
        val cw = layout.viewportWidth * zoom
        val ch = layout.contentHeight * zoom
        offsetX = if (cw <= width) (cw - width) / 2 else offsetX.coerceIn(0f, cw - width)
        offsetY = if (ch <= height) (ch - height) / 2 else offsetY.coerceIn(0f, ch - height)
    }

    /** Zooms by [factor] keeping the content under [focus] (screen px) still. */
    fun zoomBy(factor: Float, focus: Offset, layout: DocLayout) {
        val nz = (zoom * factor).coerceIn(minZoom, maxZoom)
        val cx = (focus.x + offsetX) / zoom
        val cy = (focus.y + offsetY) / zoom
        zoom = nz
        offsetX = cx * nz - focus.x
        offsetY = cy * nz - focus.y
        clamp(layout)
    }

    fun panBy(d: Offset, layout: DocLayout) {
        offsetX -= d.x
        offsetY -= d.y
        clamp(layout)
    }

    /** Page rect on screen. */
    fun screenRect(layout: DocLayout, page: Int): Rect {
        val r = layout.rects[page]
        return Rect(r.left * zoom - offsetX, r.top * zoom - offsetY, r.right * zoom - offsetX, r.bottom * zoom - offsetY)
    }

    fun visiblePages(layout: DocLayout, extra: Int = 0): IntRange {
        if (layout.pageCount == 0) return IntRange.EMPTY
        val first = layout.pageAt(offsetY / zoom)
        val last = layout.pageAt((offsetY + height) / zoom)
        return max(0, first - extra)..min(layout.pageCount - 1, last + extra)
    }

    fun currentPage(layout: DocLayout): Int = layout.pageAt((offsetY + height / 3) / zoom)

    fun scrollToPage(layout: DocLayout, page: Int, yWithinPage: Float = 0f) {
        if (page !in 0 until layout.pageCount) return
        val r = layout.rects[page]
        offsetY = (r.top + yWithinPage * r.height) * zoom - layout.gapPx() * zoom
        clamp(layout)
    }

    /** Keeps the page under the top third of the screen in place across a relayout. */
    fun anchor(layout: DocLayout): Pair<Int, Float> {
        val page = currentPage(layout)
        val r = layout.rects.getOrNull(page) ?: return 0 to 0f
        return page to ((offsetY / zoom - r.top) / r.height)
    }

    fun restore(layout: DocLayout, anchor: Pair<Int, Float>) {
        val r = layout.rects.getOrNull(anchor.first) ?: return
        offsetY = (r.top + anchor.second * r.height) * zoom
        clamp(layout)
    }

    fun fitWidth(layout: DocLayout) {
        val a = anchor(layout)
        zoom = 1f
        restore(layout, a)
    }

    fun fitPage(layout: DocLayout) {
        val page = currentPage(layout)
        val r = layout.rects.getOrNull(page) ?: return
        zoom = min(width / (r.width + 2 * layout.gapPx()), height / (r.height + 2 * layout.gapPx())).coerceIn(minZoom, maxZoom)
        offsetY = r.top * zoom - (height - r.height * zoom) / 2
        clamp(layout)
    }

    /** Hit test: page index and normalised position within it, or null. */
    fun hit(layout: DocLayout, p: Offset): PageHit? {
        for (i in visiblePages(layout)) {
            val r = screenRect(layout, i)
            if (r.contains(p)) return PageHit(i, (p.x - r.left) / r.width, (p.y - r.top) / r.height)
        }
        return null
    }

    /** Nearest page and clamped normalised position (for drags leaving a page). */
    fun hitClamped(layout: DocLayout, p: Offset, page: Int): PageHit {
        val r = screenRect(layout, page)
        return PageHit(page, ((p.x - r.left) / r.width).coerceIn(0f, 1f), ((p.y - r.top) / r.height).coerceIn(0f, 1f))
    }
}

data class PageHit(val page: Int, val nx: Float, val ny: Float)
