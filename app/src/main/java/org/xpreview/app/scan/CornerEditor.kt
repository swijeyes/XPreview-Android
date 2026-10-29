// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.xpreview.vision.Geometry
import org.xpreview.vision.Pt
import org.xpreview.vision.Quad
import kotlin.math.min

/**
 * Four draggable corner handles with a magnifier loupe (plan 6.4). Works on
 * a downscaled preview; the quad is normalised, so the final warp can use
 * the full-resolution original.
 */
@Composable
fun CornerEditor(image: ImageBitmap, quad: Quad, onChange: (Quad) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(quad)
    var active by remember { mutableIntStateOf(-1) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(Rect.Zero) }

    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "Page corners. Drag a corner to adjust the crop." }
            .pointerInput(image) {
                detectDragGestures(
                    onDragStart = { p ->
                        val pts = current.points.map { Offset(box.left + it.x.toFloat() * box.width, box.top + it.y.toFloat() * box.height) }
                        val nearest = pts.indices.minBy { (pts[it] - p).getDistance() }
                        active = if ((pts[nearest] - p).getDistance() < 64.dp.toPx()) nearest else -1
                        finger = p
                    },
                    onDrag = { change, _ ->
                        if (active < 0) return@detectDragGestures
                        change.consume()
                        finger = change.position
                        val nx = ((finger.x - box.left) / box.width).toDouble().coerceIn(0.0, 1.0)
                        val ny = ((finger.y - box.top) / box.height).toDouble().coerceIn(0.0, 1.0)
                        val pts = current.points.toMutableList()
                        pts[active] = Pt(nx, ny)
                        val q = Quad(pts[0], pts[1], pts[2], pts[3])
                        // Keep TL..BL order meaningful; refuse self-intersecting shapes.
                        if (Geometry.isConvex(q)) onChange(q)
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                )
            },
    ) {
        val s = min(size.width / image.width, size.height / image.height)
        val w = image.width * s
        val h = image.height * s
        box = Rect((size.width - w) / 2, (size.height - h) / 2, (size.width + w) / 2, (size.height + h) / 2)
        drawImage(
            image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(box.left.toInt(), box.top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()),
        )
        val pts = quad.points.map { Offset(box.left + it.x.toFloat() * w, box.top + it.y.toFloat() * h) }
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        // Dim outside the page.
        clipPath(path, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.45f), box.topLeft, box.size) }
        drawPath(path, Color(0xFF2196F3), style = Stroke(2.5.dp.toPx()))
        pts.forEachIndexed { i, p ->
            drawCircle(Color.White, 14.dp.toPx(), p)
            drawCircle(if (i == active) Color(0xFFFF9800) else Color(0xFF2196F3), 10.dp.toPx(), p)
        }
        if (active >= 0) {
            // Loupe: 2.5x around the corner, drawn away from the finger.
            val r = 56.dp.toPx()
            val center = Offset(if (finger.x < size.width / 2) size.width - r - 16.dp.toPx() else r + 16.dp.toPx(), r + 16.dp.toPx())
            val p = pts[active]
            val zoom = 2.5f
            val srcHalf = (r / zoom / s).toInt().coerceIn(4, min(image.width, image.height) / 2)
            // drawImage rejects source rects outside the bitmap: clamp near the edges.
            val ix = ((p.x - box.left) / s).toInt().coerceIn(srcHalf, image.width - srcHalf)
            val iy = ((p.y - box.top) / s).toInt().coerceIn(srcHalf, image.height - srcHalf)
            val circle = Path().apply { addOval(Rect(center, r)) }
            clipPath(circle) {
                drawRect(Color.Black, center - Offset(r, r), androidx.compose.ui.geometry.Size(2 * r, 2 * r))
                drawImage(
                    image, srcOffset = IntOffset(ix - srcHalf, iy - srcHalf), srcSize = IntSize(2 * srcHalf, 2 * srcHalf),
                    dstOffset = IntOffset((center.x - r).toInt(), (center.y - r).toInt()), dstSize = IntSize((2 * r).toInt(), (2 * r).toInt()),
                )
            }
            drawCircle(Color.White, r, center, style = Stroke(2.dp.toPx()))
            drawLine(Color(0xFFFF9800), center - Offset(10.dp.toPx(), 0f), center + Offset(10.dp.toPx(), 0f), 1.5.dp.toPx())
            drawLine(Color(0xFFFF9800), center - Offset(0f, 10.dp.toPx()), center + Offset(0f, 10.dp.toPx()), 1.5.dp.toPx())
        }
    }
}
