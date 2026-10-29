// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.xpreview.pdf.RenderFlags
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** What a single finger does. Two fingers always pan/zoom (plan 10.3). */
enum class InputMode { Navigate, Draw }

interface PdfViewCallbacks {
    fun onTap(hit: PageHit?, screen: Offset) {}
    fun onLongPress(hit: PageHit) {}
    fun onSelectDrag(hit: PageHit) {}
    fun onSelectEnd() {}
    fun onDrawStart(hit: PageHit, screen: Offset) {}
    fun onDrawMove(screen: Offset) {}
    fun onDrawEnd() {}
    fun onDrawCancel() {}
}

private val InvertMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

@Composable
fun PdfView(
    layout: DocLayout,
    viewport: Viewport,
    renderer: PageRenderer,
    docVersion: Int,
    renderFlags: Int,
    darkPages: Boolean,
    inputMode: InputMode,
    callbacks: PdfViewCallbacks,
    modifier: Modifier = Modifier,
    drawOverlay: DrawScope.(page: Int, rect: Rect) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val cb by rememberUpdatedState(callbacks)
    val mode by rememberUpdatedState(inputMode)
    val currentLayout by rememberUpdatedState(layout)
    val fling = remember { FlingState() }
    val invert = remember { ColorFilter.colorMatrix(InvertMatrix) }

    // Render requests: debounced so a pinch does not queue every intermediate level.
    LaunchedEffect(layout, docVersion, renderFlags) {
        snapshotFlow { RequestState(viewport.zoom, viewport.offsetX, viewport.offsetY, viewport.width, viewport.height) }
            .collectLatest {
                delay(40)
                if (viewport.width > 0) renderer.request(wantedKeys(layout, viewport, docVersion, renderFlags))
            }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()  // pages scrolled above the viewport must not paint over the toolbars
            .onSizeChanged { s: IntSize ->
                viewport.width = s.width.toFloat()
                viewport.height = s.height.toFloat()
                viewport.clamp(currentLayout)
            }
            .pointerInput(Unit) {
                // Mouse wheel / trackpad (Chromebook): scroll, Ctrl+wheel zooms.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll) {
                            val d = e.changes.first().scrollDelta
                            if (e.keyboardModifiers.isCtrlPressed) {
                                viewport.zoomBy(if (d.y < 0) 1.15f else 1 / 1.15f, e.changes.first().position, currentLayout)
                            } else {
                                viewport.panBy(Offset(-d.x * 60f, -d.y * 60f), currentLayout)
                            }
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling.stop()
                    val slop = viewConfiguration.touchSlop
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val l = currentLayout

                    // In Draw mode the stylus always draws; once a stylus has been seen,
                    // fingers pan instead (palm rejection, plan 10.1).
                    if (down.type == PointerType.Stylus) fling.stylusSeen = true
                    var drawing = mode == InputMode.Draw && (down.type == PointerType.Stylus || !fling.stylusSeen)
                    if (drawing) {
                        val hit = viewport.hit(l, down.position)
                        if (hit == null) drawing = false else cb.onDrawStart(hit, down.position)
                    }

                    var transform = false
                    var selecting = false
                    var panning = false
                    if (!drawing) {
                        // Pending: tap, long press, pan or pinch?
                        var moved = Offset.Zero
                        var lifted = false
                        val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) { lifted = true; return@withTimeoutOrNull true }
                                if (pressed.size > 1) { transform = true; return@withTimeoutOrNull true }
                                val c = pressed.first()
                                moved += c.positionChange()
                                tracker.addPosition(c.uptimeMillis, c.position)
                                if (moved.getDistance() > slop) {
                                    panning = true
                                    viewport.panBy(moved, l)
                                    c.consume()
                                    return@withTimeoutOrNull true
                                }
                            }
                            @Suppress("UNREACHABLE_CODE") true
                        }
                        if (decided == null) {
                            val hit = viewport.hit(l, down.position)
                            if (hit != null) { selecting = true; cb.onLongPress(hit) }
                        } else if (lifted) {
                            // Tap or double tap.
                            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                                awaitFirstDown(requireUnconsumed = false)
                            }
                            if (second == null) {
                                cb.onTap(viewport.hit(l, down.position), down.position)
                                return@awaitEachGesture
                            }
                            // Wait for the second up, then toggle zoom.
                            while (awaitPointerEvent().changes.any { it.pressed }) Unit
                            val target = if (viewport.zoom < 1.5f) 2.5f else 1f
                            fling.animateZoom(scope, viewport, l, target, second.position)
                            return@awaitEachGesture
                        }
                    }

                    // Main loop until all pointers are up.
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1 && !selecting) {
                            if (drawing) { cb.onDrawCancel(); drawing = false }
                            transform = true
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val centroid = ev.calculateCentroid(useCurrent = true)
                            if (zoom != 1f && centroid != Offset.Unspecified) viewport.zoomBy(zoom, centroid, l)
                            viewport.panBy(pan, l)
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        val c = pressed.first()
                        when {
                            drawing -> cb.onDrawMove(c.position)
                            selecting -> {
                                val h = viewport.hit(l, c.position)
                                if (h != null) cb.onSelectDrag(h)
                            }
                            transform -> viewport.panBy(c.positionChange(), l)
                            else -> {
                                tracker.addPosition(c.uptimeMillis, c.position)
                                viewport.panBy(c.positionChange(), l)
                            }
                        }
                        c.consume()
                    }
                    when {
                        drawing -> cb.onDrawEnd()
                        selecting -> cb.onSelectEnd()
                        panning && !transform -> {
                            val v = tracker.calculateVelocity()
                            fling.start(scope, viewport, l, Offset(v.x, v.y))
                        }
                    }
                }
            },
    ) {
        renderer.version.intValue  // redraw when bitmaps arrive
        val range = viewport.visiblePages(layout)
        val pageColor = if (darkPages) Color(0xFF1E1E1E) else Color.White
        val filter = if (darkPages) invert else null
        for (page in range) {
            val r = viewport.screenRect(layout, page)
            drawRect(pageColor, r.topLeft, r.size)
            val base = renderer.get(baseKey(layout, viewport, page, docVersion, renderFlags)) ?: renderer.fallback(page)
            if (base != null) {
                drawImage(
                    base, srcOffset = IntOffset.Zero, srcSize = IntSize(base.width, base.height),
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                    filterQuality = FilterQuality.Low, colorFilter = filter,
                )
            }
            val level = tileScale(layout, viewport)
            if (level != null) {
                val f = level / (layout.basePxPerPt * viewport.zoom)  // tile px per screen px
                for (k in tileKeys(layout, viewport, page, level, docVersion, renderFlags)) {
                    val img = renderer.get(k) ?: continue
                    val left = r.left + k.tx * PageRenderer.TILE / f
                    val top = r.top + k.ty * PageRenderer.TILE / f
                    drawImage(
                        img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(floor(left).toInt(), floor(top).toInt()),
                        dstSize = IntSize(ceil(img.width / f + (left - floor(left))).toInt(), ceil(img.height / f + (top - floor(top))).toInt()),
                        filterQuality = FilterQuality.Low, colorFilter = filter,
                    )
                }
            }
            clipRect(r.left, r.top, r.right, r.bottom) { drawOverlay(page, r) }
        }
    }
}

private data class RequestState(val z: Float, val x: Float, val y: Float, val w: Float, val h: Float)

/** Base bitmaps render at zoom 1 (or half that when zoomed far out). */
private fun baseScale(layout: DocLayout, viewport: Viewport): Float =
    layout.basePxPerPt * if (viewport.zoom < 0.5f) 0.5f else 1f

internal fun baseKey(layout: DocLayout, viewport: Viewport, page: Int, version: Int, flags: Int): RenderKey {
    val s = baseScale(layout, viewport)
    val ps = layout.displaySizes[page]
    var w = max(1, (ps.width * s).roundToInt())
    var h = max(1, (ps.height * s).roundToInt())
    // Very long pages (receipts, posters) are capped; tiles provide the detail.
    val maxPx = 4096 * 4096L
    var scale = s
    if (w.toLong() * h > maxPx) {
        val f = kotlin.math.sqrt(maxPx.toDouble() / (w.toLong() * h)).toFloat()
        scale *= f; w = max(1, (w * f).toInt()); h = max(1, (h * f).toInt())
    }
    return RenderKey(page, RenderKind.Base, scale, 0, 0, w, h, version, layout.rotation, flags)
}

/** Tile scale for the current zoom, in half-octave steps; null when the base bitmap suffices. */
private fun tileScale(layout: DocLayout, viewport: Viewport): Float? {
    if (viewport.zoom <= 1.15f) return null
    val steps = ceil(ln(viewport.zoom) / ln(2f) * 2f)
    return layout.basePxPerPt * 2f.pow(steps / 2f)
}

private fun tileKeys(layout: DocLayout, viewport: Viewport, page: Int, scale: Float, version: Int, flags: Int): List<RenderKey> {
    val r = viewport.screenRect(layout, page)
    val vis = Rect(max(0f, r.left), max(0f, r.top), min(viewport.width, r.right), min(viewport.height, r.bottom))
    if (vis.width <= 0 || vis.height <= 0) return emptyList()
    val ps = layout.displaySizes[page]
    val pw = max(1, (ps.width * scale).roundToInt())
    val ph = max(1, (ps.height * scale).roundToInt())
    val f = scale / (layout.basePxPerPt * viewport.zoom)
    val t = PageRenderer.TILE
    val x0 = ((vis.left - r.left) * f / t).toInt().coerceAtLeast(0)
    val x1 = ((vis.right - r.left) * f / t).toInt().coerceAtMost((pw - 1) / t)
    val y0 = ((vis.top - r.top) * f / t).toInt().coerceAtLeast(0)
    val y1 = ((vis.bottom - r.top) * f / t).toInt().coerceAtMost((ph - 1) / t)
    val out = ArrayList<RenderKey>()
    for (ty in y0..y1) for (tx in x0..x1) {
        out += RenderKey(page, RenderKind.Tile, scale, tx, ty, min(t, pw - tx * t), min(t, ph - ty * t), version, layout.rotation, flags)
    }
    return out
}

/** Priority order: current page base, other visible bases, tiles, then prefetch. */
private fun wantedKeys(layout: DocLayout, viewport: Viewport, version: Int, flags: Int): List<RenderKey> {
    val visible = viewport.visiblePages(layout)
    if (visible.isEmpty()) return emptyList()
    val current = viewport.currentPage(layout).coerceIn(visible.first, visible.last)
    val keys = ArrayList<RenderKey>()
    keys += baseKey(layout, viewport, current, version, flags)
    for (p in visible) if (p != current) keys += baseKey(layout, viewport, p, version, flags)
    tileScale(layout, viewport)?.let { s -> for (p in visible) keys += tileKeys(layout, viewport, p, s, version, flags) }
    for (p in listOf(visible.first - 1, visible.last + 1, visible.last + 2)) {
        if (p in 0 until layout.pageCount) keys += baseKey(layout, viewport, p, version, flags)
    }
    return keys
}

fun thumbKey(sizes: List<org.xpreview.pdf.PageSize>, page: Int, widthPx: Int, version: Int): RenderKey {
    val ps = sizes[page]
    val s = widthPx / ps.width
    val h = (ps.height * s).roundToInt().coerceIn(1, widthPx * org.xpreview.common.Limits.MAX_THUMBNAIL_ASPECT)
    return RenderKey(page, RenderKind.Thumb, s, 0, 0, widthPx, h, version, 0, RenderFlags.ANNOTATIONS or RenderFlags.FORMS)
}

/** Fling and animated zoom for the viewport. */
private class FlingState {
    private var job: Job? = null
    var stylusSeen = false

    fun stop() { job?.cancel(); job = null }

    fun start(scope: kotlinx.coroutines.CoroutineScope, vp: Viewport, layout: DocLayout, velocity: Offset) {
        stop()
        if (abs(velocity.x) < 50 && abs(velocity.y) < 50) return
        job = scope.launch {
            val ax = Animatable(0f)
            val ay = Animatable(0f)
            var lastX = 0f
            var lastY = 0f
            launch {
                ax.animateDecay(velocity.x, exponentialDecay(frictionMultiplier = 1.2f)) {
                    vp.panBy(Offset(value - lastX, 0f), layout); lastX = value
                }
            }
            ay.animateDecay(velocity.y, exponentialDecay(frictionMultiplier = 1.2f)) {
                vp.panBy(Offset(0f, value - lastY), layout); lastY = value
            }
        }
    }

    fun animateZoom(scope: kotlinx.coroutines.CoroutineScope, vp: Viewport, layout: DocLayout, target: Float, focus: Offset) {
        stop()
        job = scope.launch {
            val a: Animatable<Float, AnimationVector1D> = Animatable(vp.zoom)
            a.animateTo(target, tween(220)) { vp.zoomBy(value / vp.zoom, focus, layout) }
        }
    }
}

