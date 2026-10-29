// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A point in normalised image coordinates (0..1), independent of android.graphics. */
data class Pt(val x: Double, val y: Double)

/** Document corners in order top-left, top-right, bottom-right, bottom-left, normalised 0..1. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val points get() = listOf(tl, tr, br, bl)

    fun mapPoints(f: (Pt) -> Pt) = Quad(f(tl), f(tr), f(br), f(bl))

    /** Shoelace area in normalised units. */
    val area: Double
        get() {
            val p = points
            var s = 0.0
            for (i in p.indices) {
                val a = p[i]
                val b = p[(i + 1) % 4]
                s += a.x * b.y - b.x * a.y
            }
            return abs(s) / 2
        }

    companion object {
        /** Full frame inset by [inset] on each side (fallback in plan 6.3). */
        fun full(inset: Double = 0.02) =
            Quad(Pt(inset, inset), Pt(1 - inset, inset), Pt(1 - inset, 1 - inset), Pt(inset, 1 - inset))

        fun fromList(v: List<Double>) = Quad(Pt(v[0], v[1]), Pt(v[2], v[3]), Pt(v[4], v[5]), Pt(v[6], v[7]))
    }

    fun toList() = points.flatMap { listOf(it.x, it.y) }
}

object Geometry {
    /** Orders four arbitrary corners TL, TR, BR, BL (sum / difference method, B.1 step 9). */
    fun orderCorners(pts: List<Pt>): Quad {
        require(pts.size == 4)
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val rest = pts.filter { it !== tl && it !== br }.let { if (it.size == 2) it else pts - setOf(tl, br) }
        val tr = rest.minByOrNull { it.y - it.x } ?: pts[1]
        val bl = rest.maxByOrNull { it.y - it.x } ?: pts[3]
        return Quad(tl, tr, br, bl)
    }

    /** Interior angles in degrees, for a quad in pixel space. */
    fun interiorAngles(q: Quad, w: Double = 1.0, h: Double = 1.0): List<Double> {
        val p = q.points.map { Pt(it.x * w, it.y * h) }
        return p.indices.map { i ->
            val a = p[(i + 3) % 4]
            val b = p[i]
            val c = p[(i + 1) % 4]
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val d = hypot(v1x, v1y) * hypot(v2x, v2y)
            if (d == 0.0) 0.0 else Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / d).coerceIn(-1.0, 1.0)))
        }
    }

    fun isConvex(q: Quad): Boolean {
        val p = q.points
        var sign = 0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Plausible document shape (B.1 step 5), with the quad in pixel space of [w] x [h]. */
    fun isPlausible(q: Quad, w: Double, h: Double, minArea: Double = 0.15): Boolean {
        if (!isConvex(q) || q.area < minArea) return false
        if (interiorAngles(q, w, h).any { it < 60 || it > 120 }) return false
        val p = q.points.map { Pt(it.x * w, it.y * h) }
        val sides = p.indices.map { hypot(p[it].x - p[(it + 1) % 4].x, p[it].y - p[(it + 1) % 4].y) }
        return sides.min() / sides.max() > 0.2
    }

    /**
     * Output size for the warp (plan 6.5): longest opposite edges, capped so
     * the long side is at most [maxSide]. [w] and [h] are the source pixel size.
     */
    fun outputSize(q: Quad, w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        fun d(a: Pt, b: Pt) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        var ow = max(d(q.tl, q.tr), d(q.bl, q.br))
        var oh = max(d(q.tl, q.bl), d(q.tr, q.br))
        val s = min(1.0, maxSide / max(ow, oh))
        ow *= s; oh *= s
        return max(1, ow.roundToInt()) to max(1, oh.roundToInt())
    }

    enum class PaperSize(val ratio: Double, val widthPt: Float, val heightPt: Float) {
        A4(297.0 / 210.0, 595.28f, 841.89f),
        Letter(11.0 / 8.5, 612f, 792f),
        A5(210.0 / 148.0, 419.53f, 595.28f),
        Legal(14.0 / 8.5, 612f, 1008f),
    }

    /** Standard paper size whose aspect ratio is within [tolerance] of w:h (portrait or landscape). */
    fun snapPaper(w: Int, h: Int, tolerance: Double = 0.04): PaperSize? {
        val r = max(w, h).toDouble() / min(w, h)
        return PaperSize.entries.minByOrNull { abs(it.ratio - r) / it.ratio }
            ?.takeIf { abs(it.ratio - r) / it.ratio <= tolerance }
    }

    /** Maximum corner movement between two quads, relative to the unit diagonal. */
    fun maxCornerShift(a: Quad, b: Quad): Double =
        a.points.zip(b.points).maxOf { (p, q) -> hypot(p.x - q.x, p.y - q.y) } / sqrt(2.0)

    /** Exponential smoothing of corners (B.2). */
    fun smooth(prev: Quad, next: Quad, alpha: Double): Quad = Quad(
        lerp(prev.tl, next.tl, alpha), lerp(prev.tr, next.tr, alpha),
        lerp(prev.br, next.br, alpha), lerp(prev.bl, next.bl, alpha),
    )

    private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /** Rotates a normalised quad by quarter turns clockwise, keeping TL..BL order. */
    fun rotate(q: Quad, quarterTurns: Int): Quad {
        var r = q
        repeat(((quarterTurns % 4) + 4) % 4) { r = orderCorners(r.points.map { Pt(1 - it.y, it.x) }) }
        return r
    }
}
