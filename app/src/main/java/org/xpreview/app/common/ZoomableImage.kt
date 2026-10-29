// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * Pan/zoom for a single image, same gesture rules as the PDF view: pinch
 * zooms, one finger pans only when zoomed (so a pager can still swipe),
 * double tap toggles 1x / 2.5x.
 */
@Composable
fun ZoomableImage(image: ImageBitmap, description: String, modifier: Modifier = Modifier) {
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clampOffset(o: Offset): Offset {
        val maxX = (size.width * (scale - 1)) / 2
        val maxY = (size.height * (scale - 1)) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(image) {
                detectTapGestures(onDoubleTap = { p ->
                    if (scale > 1.1f) { scale = 1f; offset = Offset.Zero } else {
                        scale = 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        offset = clampOffset((center - p) * 1.5f)
                    }
                })
            }
            .pointerInput(image) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val e = awaitPointerEvent()
                        val pointers = e.changes.count { it.pressed }
                        if (pointers >= 2 || scale > 1.01f) {
                            val z = e.calculateZoom()
                            val c = e.calculateCentroid()
                            val newScale = (scale * z).coerceIn(1f, 10f)
                            if (c != Offset.Unspecified) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                offset = (offset - (c - center)) * (newScale / scale) + (c - center)
                            }
                            scale = newScale
                            offset = clampOffset(offset + e.calculatePan())
                            e.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (e.changes.any { it.pressed })
                }
            },
    ) {
        Image(
            BitmapPainter(image), description, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
        )
    }
}
