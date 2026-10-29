// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.scan

import org.xpreview.vision.Geometry
import org.xpreview.vision.Quad

enum class Hint(val text: String) {
    NotFound("Point the camera at a document"),
    MoveCloser("Move closer"),
    HoldSteady("Hold steady"),
    LowLight("Low light — try the torch"),
    Blurry("Hold still to focus"),
    Ready("Capturing…"),
    Captured("Captured — next page"),
}

data class LiveState(
    val quad: Quad? = null,
    /** Width / height of the upright analysis frame. */
    val frameAspect: Float = 0.75f,
    val hint: Hint = Hint.NotFound,
    val steadyProgress: Float = 0f,
    val captureNow: Boolean = false,
)

/**
 * Temporal logic for live detection (Appendix B.2): exponential smoothing,
 * tolerance for single dropped frames, steadiness over the last N frames,
 * a sharpness gate, and auto-capture that re-arms only after the document
 * leaves or changes.
 */
class LiveTracker(
    private val steadyFrames: Int = 8,
    private val steadyShift: Double = 0.02,
    private val sharpThreshold: Double = 60.0,
    private val minArea: Double = 0.2,
) {
    private var smoothed: Quad? = null
    private var missed = 0
    private val history = ArrayDeque<Quad>()
    private var armed = true
    private var capturedQuad: Quad? = null
    private var capturedAt = 0L
    private var absent = 0

    fun reset() { smoothed = null; history.clear(); missed = 0 }

    fun update(q: Quad?, sharpness: Double, brightness: Double, aspect: Float, autoCapture: Boolean, now: Long): LiveState {
        if (q == null) {
            missed++
            absent++
            if (missed > 1) reset()  // ignore a single dropped frame
        } else {
            missed = 0
            absent = 0
            smoothed = smoothed?.let { Geometry.smooth(it, q, 0.5) } ?: q
            history.addLast(smoothed!!)
            while (history.size > steadyFrames) history.removeFirst()
        }
        val cur = smoothed
        if (!armed) {
            val moved = cur != null && capturedQuad != null && Geometry.maxCornerShift(cur, capturedQuad!!) > 0.1
            if (absent >= 3 || (now - capturedAt > 3000 && moved)) armed = true
        }
        val steadyCount = if (history.size < 2) 0 else history.zipWithNext().takeLastWhile { (a, b) ->
            Geometry.maxCornerShift(a, b) < steadyShift
        }.size
        val steady = steadyCount >= steadyFrames - 1
        val sharp = sharpness >= sharpThreshold
        val hint = when {
            cur == null -> if (brightness < 45) Hint.LowLight else Hint.NotFound
            !armed -> Hint.Captured
            cur.area < minArea -> Hint.MoveCloser
            !steady -> Hint.HoldSteady
            !sharp -> if (brightness < 45) Hint.LowLight else Hint.Blurry
            else -> Hint.Ready
        }
        val capture = autoCapture && armed && hint == Hint.Ready
        if (capture) {
            armed = false
            capturedQuad = cur
            capturedAt = now
        }
        return LiveState(cur, aspect, hint, steadyCount / (steadyFrames - 1f), capture)
    }

    /** A manual capture also disarms auto-capture for this page. */
    fun onManualCapture(now: Long) {
        armed = false
        capturedQuad = smoothed
        capturedAt = now
    }
}
